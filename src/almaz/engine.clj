(ns almaz.engine
  "Runs one filter layer over the composite below it, tile by tile.

   The tiles nearest the focus point (the centre of the viewport) go first,
   each blended into the layer's output with almaz.infer.tile's crossfades, so
   the result shows up where the user is looking and grows outwards. All pixel
   work is native: tile extraction (which composites the layers below on the
   fly), inference straight from native buffers, and the crossfaded write."
  (:require [almaz.color :as c]
            [almaz.doc :as d]
            [almaz.geom :as g]
            [almaz.infer.protocol :as p]
            [almaz.infer.tile :as tile]
            [almaz.lens :as lens]
            [almaz.raw :as raw]
            [almaz.pixels :as px]
            [jolt.ffi :as ffi]))

(defn layer-refs
  "Doc `doc`'s `layers` as the pixel engine's stack entries: light layers
   carry their adjustments as :tone, colour adjustments their tables
   (almaz.color/table, or `:table` (fn [layer kind settings]) to look them
   up some other way), the vignette its radial weight around the crop."
  ([doc layers] (layer-refs doc layers nil))
  ([doc layers {:keys [table] :or {table (fn [_ kind p] (c/table kind p))}}]
   (mapv (fn [{:keys [img mask opacity enabled kind] :as l}]
           (let [[ck cp] (d/colour l)]
             (cond-> {:img img :mask mask :opacity opacity :enabled enabled}
               (d/tone l) (assoc :tone (d/tone l))
               ck (merge (when enabled (table l ck cp)))
               (and (= :vignette kind) doc (:w doc)) (assoc :radial (c/radial cp (g/crop-rect doc))))))
         layers)))

(defn- model-op [{:keys [backend session spec]} detail]
  (let [{:keys [block overlap inputs outputs]} spec
        shape [1 3 block block]
        n (* 3 block block)]
    {:block block :overlap overlap
     :run (fn [in out _]
            (p/run-native backend session
                          [{:name (first inputs) :shape shape :ptr in :count n}]
                          [{:name (first outputs) :ptr out :count n}])
            (when (pos? detail)
              (px/detail! out in block {:sigma 1.5 :amount detail})))}))

(defn focus-op
  "Clarity (local contrast at `radius` px) then gaussian blur (sigma `blur`
   px) over tiles large enough, with enough overlap, for both."
  [{:keys [focus-clarity focus-radius focus-blur]}]
  (let [clarity (double (or focus-clarity 0.0)) radius (double (or focus-radius 80.0))
        blur (double (or focus-blur 0.0))
        reach (max (if (zero? clarity) 0.0 radius) blur)
        overlap (max 16 (* 16 (long (Math/ceil (/ (* 3.0 reach) 16)))))
        block (cond (<= overlap 64) 256 (<= overlap 160) 512 (<= overlap 320) 1024 :else 2048)]
    {:block block :overlap overlap
     :run (fn [in out n]
            (ffi/copy in out (* 4 n))
            (when-not (zero? clarity) (px/local-contrast! out block block {:sigma radius :amount clarity}))
            (when (pos? blur) (px/gauss! out block block blur)))}))

(defn op-for
  "The tile operation for a layer row: {:block :overlap :run (fn [in out n])},
   where `run` turns the planar float tile at `in` into `out` (n floats).
   `session` is the loaded model session (almaz.models/session) for AI filters."
  [{:keys [kind model detail amount radius] :as layer} session]
  (case [kind model]
    [:denoise :ai] (model-op session (or detail 0.0))
    [:sharpen :ai] (model-op session 0.0)
    [:focus :classic] (focus-op layer)
    [:sharpen :classic]
    (let [block 256
          radius (double (or radius 1.5))]
      {:block block :overlap (max 16 (long (* 8 radius)))
       :run (fn [in out n]
              (ffi/copy in out (* 4 n))
              (px/unsharp! out block {:sigma radius :amount (or amount 1.0)}))})))

(defn model-id
  "Which model a layer needs, or nil for a classic filter."
  [{:keys [kind model]}]
  (when (= :ai model) kind))

(def ^:private empty-mask 1e-3)

(defn- tile-step!
  "Run one tile into `out`; false when the mask leaves it untouched (skipped).
   `ctx` is the process! opts plus the grid and the two tile buffers."
  [{:keys [base below out mask g in res n] {:keys [run]} :op} t done]
  (if (and mask (< (px/mask-max mask t) empty-mask))
    false
    (do (px/tile-extract! base below t in)
        (run in res n)
        (px/tile-write! out res t (tile/ramps g t done))
        true)))

(defn process!
  "Filter the composite of `base` + `below` into the image `out`, tile by tile.
   opts: :op (op-for), :focus [x y] (image coords to start from), :mask (the
   layer's mask: tiles it leaves untouched are skipped), :progress
   (fn [done total]) after each tile, :cancelled? (fn []) checked between
   tiles. Returns :done, :partial (some tiles skipped) or :cancelled."
  [{:keys [base op focus progress cancelled?]
    :or {progress (fn [_ _]) cancelled? (constantly false)} :as opts}]
  (let [{:keys [block overlap]} op
        w (px/width base) h (px/height base)
        g (tile/grid w h block overlap)
        n (* 3 block block)
        in (ffi/alloc (* 4 n))
        res (ffi/alloc (* 4 n))]
    (try
      (loop [[t & more] (tile/by-focus g (or focus [(/ w 2) (/ h 2)])) done #{} seen 0 skipped? false]
        (cond
          (nil? t) (if skipped? :partial :done)
          (cancelled?) :cancelled
          :else (let [ran? (tile-step! (assoc opts :g g :in in :res res :n n) t done)]
                  (progress (inc seen) (count g))
                  (recur more (if ran? (conj done (:i t)) done) (inc seen) (or skipped? (not ran?))))))
      (finally
        (ffi/free in)
        (ffi/free res)))))

;; ---- repair --------------------------------------------------------------------
(def repair-threshold
  "Mask values over this are repaired: the whole painted area, its feather
   included, so the blend at its edge only meets repaired pixels."
  0.02)

(defn- context
  "The crop to repair area `r` [x y w h] in: grown by as much again as the
   area (at least 48 px) for texture to draw from, inside the image."
  [[x y w h] iw ih]
  (let [m (max 48 (max w h))
        x0 (max 0 (- x m)) y0 (max 0 (- y m))
        x1 (min iw (+ x w m)) y1 (min ih (+ y h m))]
    [x0 y0 (- x1 x0) (- y1 y0)]))

(defn- overlaps? [[ax ay aw ah] [bx by bw bh]]
  (and (< ax (+ bx bw)) (< bx (+ ax aw)) (< ay (+ by bh)) (< by (+ ay ah))))

(defn repair!
  "Fill what layer mask `mask` paints, in `out`, from its surroundings in
   the composite of `base` + `below` (almaz.pixels/inpaint!). Each painted
   area is filled within a crop around it, read from `out` so neighbouring
   areas see each other's repairs.

   Without :dirty, `out` starts over as that composite and every area is
   filled. With :dirty [x y w h] (the mask's change since `out` was last
   finished over the same composite below), only that rect goes back to the
   composite and only the areas touching it are filled again. opts:
   :progress (fn [done total]), :cancelled? (fn []) between areas. Returns
   :done or :cancelled."
  [{:keys [base below out mask dirty progress cancelled?]
    :or {progress (fn [_ _]) cancelled? (constantly false)}}]
  (let [w (px/width base) h (px/height base)
        areas (px/mask-regions mask repair-threshold 0)
        todo (if dirty
               (do (let [[x y dw dh] dirty
                         n (* 3 dw dh) buf (ffi/alloc (* 4 n))]
                     (try (px/tile-extract! base below {:x x :y y :w dw :h dh} buf)
                          (px/tile-write! out buf {:x x :y y :w dw :h dh} {})
                          (finally (ffi/free buf))))
                   (filterv #(overlaps? % dirty) areas))
               (do (px/composite-into! out base below) areas))
        crops (mapv #(context % w h) todo)]
    (loop [[r & more] crops [a & as] todo i 0]
      (cond
        (nil? r) :done
        (cancelled?) :cancelled
        :else
        (let [[x y cw ch] r
              n (* 3 cw ch)
              src (ffi/alloc (* 4 n)) res (ffi/alloc (* 4 n)) hole (ffi/alloc (* cw ch))]
          (try
            (px/tile-extract! out [] {:x x :y y :w cw :h ch} src)
            (px/mask-hole! mask r repair-threshold hole)
            (px/inpaint! src cw ch hole (+ 1 (first a) (* 7919 (second a))) res)
            (px/tile-write! out res {:x x :y y :w cw :h ch} {})
            (finally (ffi/free src) (ffi/free res) (ffi/free hole)))
          (progress (inc i) (count crops))
          (recur more as (inc i)))))))

;; ---- lens correction -----------------------------------------------------------
(def ^:private embedded
  (memoize (fn [path] (when (and path (raw/raw-file? path)) (px/sony-corrections path)))))

(defn lens-opts
  "A lens layer's settings as almaz.lens/plan options."
  [{:keys [lens-method lens-dist lens-tca lens-vig lens-fill lens-id]}]
  {:method (or lens-method :auto) :dist? lens-dist :tca? lens-tca :vig? lens-vig
   :autofill? lens-fill :lens lens-id})

(defn lens-photo
  "What almaz.lens/plan needs to know of doc `dc`: its size, EXIF and the
   corrections its raw file embeds."
  [db {:keys [id path w h]}]
  {:w w :h h :exif (d/exif db id) :embedded (embedded path)})

(defn lens-plan
  "How lens layer `layer` corrects doc `dc` (almaz.lens/plan), or nil."
  [db dc layer]
  (when (:w dc) (lens/plan (lens-photo db dc) (lens-opts layer))))

(def ^:private lens-band "Rows per lens-correction step." 128)

(defn lens!
  "Undistort the composite of `base` + `below` into `out` by `plan` (an
   almaz.lens plan; nil copies it), a band of rows at a time. opts:
   :progress (fn [done total]), :cancelled? (fn []) between bands. Returns
   :done or :cancelled."
  [{:keys [base below out plan progress cancelled?]
    :or {progress (fn [_ _]) cancelled? (constantly false)}}]
  (let [w (px/width base) h (px/height base)
        src (if (seq below) (doto (px/new-image w h) (px/composite-into! base below)) base)
        bands (vec (range 0 h lens-band))]
    (try
      (if-not plan
        (do (px/composite-into! out base below) :done)
        (loop [[y & more] bands i 0]
          (cond
            (nil? y) :done
            (cancelled?) :cancelled
            :else (do (px/lens-warp! out src plan y (min h (+ y lens-band)))
                      (progress (inc i) (count bands))
                      (recur more (inc i))))))
      (finally (when-not (= src base) (px/free-image! src))))))
