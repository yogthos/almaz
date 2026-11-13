(ns almaz.pixels
  "The native pixel engine (native/img/libalmazimg): 16-bit images with a mip
   pyramid, low-resolution feathered masks, layer-stack compositing and
   viewport rendering, tiled-inference helpers, estimators and file I/O.

   Images and masks are native handles (pointers). One defcfn per C function,
   then thin wrappers that marshal Clojure values: a layer stack is a seq of
   {:img :mask :opacity :enabled} maps, written into a C AlmzLayer array.
   Long calls are :blocking so a worker thread in C doesn't pin the GC."
  (:require [jolt.ffi :as ffi]))

;; ---- bindings ----------------------------------------------------------------
(ffi/defcfn img-new*        "almz_img_new"        [:int32 :int32] :pointer :blocking)
(ffi/defcfn img-wrap*       "almz_img_wrap"       [:int32 :int32 :pointer] :pointer :blocking)
(ffi/defcfn img-free*       "almz_img_free"       [:pointer] :void)
(ffi/defcfn img-width*      "almz_img_width"      [:pointer] :int32)
(ffi/defcfn img-height*     "almz_img_height"     [:pointer] :int32)
(ffi/defcfn img-get*        "almz_img_get"        [:pointer :int32 :int32 :pointer] :void)
(ffi/defcfn img-set-f32*    "almz_img_set_f32"    [:pointer :pointer] :void :blocking)

(ffi/defcfn mask-new*       "almz_mask_new"       [:int32 :int32 :int32 :float] :pointer)
(ffi/defcfn mask-free*      "almz_mask_free"      [:pointer] :void)
(ffi/defcfn mask-fill*      "almz_mask_fill"      [:pointer :float] :void)
(ffi/defcfn mask-invert*    "almz_mask_invert"    [:pointer] :void :blocking)
(ffi/defcfn mask-feather*   "almz_mask_set_feather" [:pointer :float] :void :blocking)
(ffi/defcfn mask-stroke*    "almz_mask_stroke"
  [:pointer :double :double :double :double :double :double :double :int32] :void)
(ffi/defcfn mask-max*       "almz_mask_max"       [:pointer :int32 :int32 :int32 :int32] :float :blocking)
(ffi/defcfn mask-sample*    "almz_mask_sample"    [:pointer :double :double] :float)
(ffi/defcfn mask-copy*      "almz_mask_copy"      [:pointer] :pointer :blocking)
(ffi/defcfn mask-take-dirty* "almz_mask_take_dirty" [:pointer :pointer] :int32)
(ffi/defcfn mask-add-dirty*  "almz_mask_add_dirty"  [:pointer :int32 :int32 :int32 :int32] :void)
(ffi/defcfn mask-restore*   "almz_mask_restore"   [:pointer :pointer] :int32 :blocking)
(ffi/defcfn mask-regions*   "almz_mask_regions"   [:pointer :float :int32 :pointer :int32] :int32 :blocking)
(ffi/defcfn mask-hole*      "almz_mask_hole"      [:pointer :int32 :int32 :int32 :int32 :float :pointer] :void :blocking)
(ffi/defcfn inpaint*        "almz_inpaint"        [:pointer :int32 :int32 :pointer :int32 :pointer] :int32 :blocking)

(ffi/defcfn render*         "almz_render"
  [:pointer :pointer :int32 :pointer :pointer :pointer :pointer
   :pointer :int32 :int32 :int32 :int32 :int32 :int32 :pointer] :void)

(ffi/defcfn composite-into* "almz_composite_into" [:pointer :pointer :pointer :int32] :void :blocking)
(ffi/defcfn tile-extract*   "almz_tile_extract"
  [:pointer :pointer :int32 :int32 :int32 :int32 :int32 :pointer] :void :blocking)
(ffi/defcfn tile-write*     "almz_tile_write"
  [:pointer :pointer :int32 :int32 :int32 :int32 :int32 :int32 :int32 :int32] :void :blocking)
(ffi/defcfn tile-gauss*     "almz_tile_gauss"     [:pointer :int32 :int32 :float] :void :blocking)
(ffi/defcfn tile-local-contrast* "almz_tile_local_contrast"
  [:pointer :int32 :int32 :float :float :float :float] :void :blocking)
(ffi/defcfn tile-unsharp*   "almz_tile_unsharp"   [:pointer :int32 :int32 :float :float] :void :blocking)
(ffi/defcfn tile-detail*    "almz_tile_detail"
  [:pointer :pointer :int32 :int32 :float :float] :void :blocking)

(ffi/defcfn lens-warp*      "almz_lens_warp"
  [:pointer :pointer :double :double :double :int32 :pointer :double :pointer :int32 :int32] :void :blocking)
(ffi/defcfn focus*          "almz_focus"          [:pointer :pointer :float :float :float] :int32 :blocking)
(ffi/defcfn sony-corrections* "almz_sony_corrections" [:pointer :pointer :pointer :pointer :int32] :int32 :blocking)
(ffi/defcfn seg-input*      "almz_seg_input"
  [:pointer :pointer :int32 :int32 :pointer :pointer :pointer] :int32 :blocking)
(ffi/defcfn mask-set*       "almz_mask_set"       [:pointer :pointer :int32 :int32 :int32] :void :blocking)
(ffi/defcfn estimate*       "almz_estimate"       [:pointer :pointer :pointer] :void :blocking)

(ffi/defcfn load*           "almz_load"           [:pointer :pointer] :pointer :blocking)
(ffi/defcfn load-jpeg-mem*  "almz_load_jpeg_mem"  [:pointer :size_t :pointer] :pointer :blocking)
(ffi/defcfn from-rgb8*      "almz_from_rgb8"      [:pointer :int32 :int32] :pointer :blocking)
(ffi/defcfn save*           "almz_save"
  [:pointer :pointer :int32 :pointer :int32 :int32 :pointer :int32 :int32 :int32 :int32 :pointer :pointer] :int32 :blocking)
(ffi/defcfn save-estimate*  "almz_save_estimate"
  [:pointer :pointer :int32 :pointer :int32 :int32 :int32 :int32 :int32 :int32 :pointer :pointer] :int64 :blocking)
(ffi/defcfn meta-read*      "almz_meta_read"      [:pointer :pointer] :int32 :blocking)
(ffi/defcfn free-string*    "almz_free_string"    [:pointer] :void)
(ffi/defcfn abi*           "almz_abi"            [] :int32)
(ffi/defcfn tone-lut*       "almz_tone_lut"       [:pointer :pointer] :void)
(ffi/defcfn tone-apply*     "almz_tone_apply"     [:pointer :pointer] :void)
(ffi/defcfn gpu-set-enabled* "almz_gpu_set_enabled" [:int32] :void)
(ffi/defcfn gpu-active*     "almz_gpu_active"     [] :int32)
(ffi/defcfn histogram*      "almz_histogram"      [:pointer :pointer :int32 :pointer :pointer] :void :blocking)
(ffi/defcfn histogram-draw* "almz_histogram_draw" [:pointer :int32 :int32 :pointer :pointer] :void)

;; ---- layout check ------------------------------------------------------------
(def abi
  "The native struct layouts and signatures this namespace uses (ALMZ_ABI in
   almazimg.h)."
  4)

(defn abi-problem
  "Why the loaded libalmazimg can't be used with this code, or nil. A built
   binary loads the library from where it was built, so rebuilding one
   without the other passes structs of the wrong shape (a crash, not an
   error)."
  ([] (abi-problem (abi*)))
  ([lib]
   (when (not= lib abi)
     (str "libalmazimg has struct layout " lib " but this build writes " abi
          ": rebuild them together (native/img/build.sh, then the app)"))))

(when-let [p (abi-problem)] (throw (ex-info p {:lib (abi*) :expected abi})))

;; ---- errors ------------------------------------------------------------------
(defn- call-with-err
  "Call (f errslot); when it answers NULL / non-zero, throw the shim's message."
  [what f ok?]
  (with-open [a (ffi/confined-arena)]
    (let [errslot (ffi/alloc a :pointer)]
      (ffi/write errslot :pointer ffi/null 0)
      (let [r (f errslot)]
        (if (ok? r)
          r
          (let [ep (ffi/read errslot :pointer 0)
                msg (when-not (ffi/null? ep)
                      (let [m (ffi/ptr->string ep)] (free-string* ep) m))]
            (throw (ex-info (str what ": " (or msg "failed")) {}))))))))

(defn- non-null? [p] (and p (not (ffi/null? p))))

;; ---- images ------------------------------------------------------------------
(defn new-image [w h] (img-new* (int w) (int h)))
(defn wrap-image
  "Adopt a malloc'd w*h*3 uint16 buffer as an image (takes ownership)."
  [w h data] (img-wrap* (int w) (int h) data))
(defn free-image! [img] (when (non-null? img) (img-free* img)))
(defn width [img] (img-width* img))
(defn height [img] (img-height* img))

(defn pixel
  "Level-0 pixel as [r g b] floats 0..1."
  [img x y]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (* 4 3))]
      (img-get* img (int x) (int y) p)
      (mapv double (ffi/read-array p :float 3)))))

(defn set-pixels!
  "Overwrite the image from interleaved RGB floats 0..1 (a float-array)."
  [img data]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (* 4 (alength data)))]
      (ffi/write-array p :float data)
      (img-set-f32* img p))))

;; ---- masks -------------------------------------------------------------------
(defn new-mask
  "A mask for a w x h image stored at 1/div resolution, filled with `v`."
  [w h div v] (mask-new* (int w) (int h) (int div) (float v)))
(defn free-mask! [m] (when (non-null? m) (mask-free* m)))
(defn mask-fill! [m v] (mask-fill* m (float v)))
(defn mask-invert! [m] (mask-invert* m))
(defn mask-feather!
  "Set the feather (gaussian sigma, image px) and rebuild the soft mask."
  [m sigma] (mask-feather* m (float sigma)))
(defn mask-max
  "The largest feathered mask value over the image rectangle."
  [m {:keys [x y w h]}]
  (double (mask-max* m (int x) (int y) (int w) (int h))))

(defn mask-at [m x y] (double (mask-sample* m (double x) (double y))))

(defn mask-copy "An independent copy of mask `m` (free it with free-mask!)." [m] (mask-copy* m))

(defn mask-take-dirty!
  "The image rect [x y w h] the mask changed in since the last call, or nil;
   either way it starts over."
  [m]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (* 4 4))]
      (when (pos? (mask-take-dirty* m p))
        (vec (ffi/read-array p :int32 4))))))

(defn mask-add-dirty!
  "Mark rect [x y w h] changed again (a job took it and didn't finish)."
  [m [x y w h]] (mask-add-dirty* m (int x) (int y) (int w) (int h)))

(defn mask-restore!
  "Put the pixels of `snapshot` (a mask-copy of it) back into `m`."
  [m snapshot]
  (when-not (zero? (mask-restore* m snapshot))
    (throw (ex-info "mask snapshot of another size" {}))))

(def ^:private max-regions 256)

(defn mask-regions
  "[[x y w h] ...] around the mask's areas over `thr` (image px), grown by
   `margin`, overlapping ones merged."
  [m thr margin]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (* 4 4 max-regions))
          n (mask-regions* m (float thr) (int margin) p (int max-regions))]
      (mapv vec (partition 4 (vec (ffi/read-array p :int32 (* 4 n))))))))

(defn mask-hole!
  "Write 1 where the mask is over `thr` at each pixel of rect [x y w h] into
   the native byte buffer `out` (w * h)."
  [m [x y w h] thr out]
  (mask-hole* m (int x) (int y) (int w) (int h) (float thr) out))

(defn mask-hole
  "mask-hole! into a byte-array."
  [m [_ _ w h :as rect] thr]
  (let [p (ffi/alloc (* w h))]
    (try (mask-hole! m rect thr p)
         (byte-array (ffi/read-array p (* w h)))
         (finally (ffi/free p)))))

(defn stroke!
  "A soft round brush segment from [x0 y0] to [x1 y1], image coordinates."
  [m [x0 y0] [x1 y1] {:keys [radius hardness flow erase?] :or {hardness 0.5 flow 1.0}}]
  (mask-stroke* m (double x0) (double y0) (double x1) (double y1)
                (double radius) (double hardness) (double flow) (if erase? 1 0)))

;; ---- light adjustments -------------------------------------------------------
(def ^:private tone-fields [:exposure :contrast :highlights :shadows :whites :blacks :saturation])
(def ^:private tone-layout
  (ffi/layout [:struct [[:exposure :float] [:contrast :float] [:highlights :float] [:shadows :float]
                        [:whites :float] [:blacks :float] [:saturation :float]]]))
(def ^:private tone-n 4096)

(defn- write-tone [arena tone]
  (let [p (ffi/alloc arena (ffi/sizeof tone-layout))]
    (ffi/write p tone-layout (into {} (map (fn [k] [k (float (get tone k 0.0))])) tone-fields) 0)
    p))

(defn apply-tone
  "Apply light adjustments `tone` ({:exposure EV, :contrast ... -1..1}) to an
   sRGB colour [r g b] (0..1)."
  [tone rgb]
  (with-open [a (ffi/confined-arena)]
    (let [c (ffi/alloc a (* 4 3))]
      (ffi/write-array c :float (float-array (map float rgb)))
      (tone-apply* (write-tone a tone) c)
      (mapv double (ffi/read-array c :float 3)))))

;; ---- layer stacks ------------------------------------------------------------
;; An entry is {:img :mask :opacity :enabled} for a filter's output, or an
;; adjustment computed as it is drawn: :tone (light adjustments, the curve
;; tabulated here), :curves (3 x 4096 floats, per channel), :cube {:n :data}
;; (an n^3 RGB map, almaz.color), :radial [cx cy rx ry inner falloff].
(def ^:private layer-layout
  (ffi/layout [:struct [[:img :pointer] [:mask :pointer] [:opacity :float] [:enabled :int32]
                        [:lut :pointer] [:saturation :float]
                        [:cube-n :int32] [:cube :pointer] [:radial [:array :float 6]]]]))
(def ^:private layer-size (ffi/sizeof layer-layout))

(defn- floats [arena ^floats a]
  (doto (ffi/alloc arena (* 4 (alength a))) (ffi/write-array :float a)))

(defn- curve-table
  "The layer's per-channel curves in `arena`: :curves as given, or the light
   adjustments' one curve for all three channels."
  [arena {:keys [tone curves]}]
  (cond
    curves (floats arena curves)
    tone (let [p (ffi/alloc arena (* 3 4 tone-n))]
           (tone-lut* (write-tone arena tone) p)
           (ffi/copy p (+ p (* 4 tone-n)) (* 4 tone-n))
           (ffi/copy p (+ p (* 8 tone-n)) (* 4 tone-n))
           p)))

(defn- layer-struct
  "One layer's AlmzLayer fields, its tables written into `arena`."
  [arena {:keys [img mask opacity enabled tone cube radial] :as l}]
  (let [lut (curve-table arena l)
        cube-p (when cube (floats arena (:data cube)))
        sat (float (get tone :saturation 0.0))]
    {:img (or img ffi/null) :mask (or mask ffi/null)
     :opacity (float (or opacity 1.0))
     :enabled (if (and enabled (or img lut cube-p (not (zero? sat)))) 1 0)
     :lut (or lut ffi/null)
     :saturation sat
     :cube-n (int (if cube-p (:n cube) 0))
     :cube (or cube-p ffi/null)
     :radial (mapv float (or radial [0 0 0 0 0 0]))}))

(defn- write-layers
  "An AlmzLayer array for `layers` in `arena` (NULL when empty)."
  [arena layers]
  (if (empty? layers)
    ffi/null
    (let [arr (ffi/alloc arena (* layer-size (count layers)))]
      (doseq [[i l] (map-indexed vector layers)]
        (ffi/write arr layer-layout (layer-struct arena l) (* i layer-size)))
      arr)))

;; ---- histogram ---------------------------------------------------------------
(defn- ints [arena xs]
  (when xs
    (doto (ffi/alloc arena (* 4 (count xs))) (ffi/write-array :int32 (int-array (map #(Math/round (double %)) xs))))))

(defn- doubles* [arena xs]
  (doto (ffi/alloc arena (* 8 (count xs))) (ffi/write-array :double (double-array (map double xs)))))

(defn histogram
  "256-bin histograms of the composite: {:r :g :b :l (Rec. 709 luma)},
   within the source rect `clip` [x y w h] (default: all of it)."
  [base layers & [clip]]
  (with-open [a (ffi/confined-arena)]
    (let [out (ffi/alloc a (* 4 4 256))]
      (histogram* base (write-layers a layers) (int (count layers)) (or (ints a clip) ffi/null) out)
      (let [v (vec (ffi/read-array out :uint32 (* 4 256)))]
        {:r (subvec v 0 256) :g (subvec v 256 512) :b (subvec v 512 768) :l (subvec v 768 1024)}))))

(defn draw-histogram!
  "Compute the composite's histogram and draw it into the RGBA8 w x h buffer
   `out` over `bg` [r g b a]."
  [base layers {:keys [out w h bg clip]}]
  (with-open [a (ffi/confined-arena)]
    (let [hist (ffi/alloc a (* 4 4 256))
          bgp (ffi/alloc a 4)]
      (ffi/write-array bgp (byte-array (map unchecked-byte (or bg [0 0 0 255]))))
      (histogram* base (write-layers a layers) (int (count layers)) (or (ints a clip) ffi/null) hist)
      (histogram-draw* hist (int w) (int h) out bgp))))

;; ---- rendering ---------------------------------------------------------------
(defn render!
  "Render base + layers into the RGBA8 buffer `out` (out-w x out-h), within
   :rect [x y w h] (default: all of it). :xf maps screen to source pixels
   (almaz.geom's affine); or screen = image * :scale + (:ox, :oy). Outside
   the source rect :clip (default: the image) is :bg. :overlay is a mask
   drawn as :tint [r g b strength]."
  [base layers {:keys [xf clip ox oy scale overlay tint out out-w out-h rect bg]}]
  (with-open [a (ffi/confined-arena)]
    (let [[rx ry rw rh] (or rect [0 0 out-w out-h])
          larr (write-layers a layers)
          tp (when tint (let [p (ffi/alloc a (* 4 4))]
                          (ffi/write-array p :float (float-array (map float tint))) p))
          bgp (let [p (ffi/alloc a 4)
                    [r g b al] (or bg [0 0 0 255])]
                (ffi/write-array p (byte-array (map unchecked-byte [r g b al]))) p)]
      (render* base larr (int (count layers))
               (doubles* a (or xf (let [s (double scale)] [(/ 1.0 s) 0.0 (/ (- ox) s) 0.0 (/ 1.0 s) (/ (- oy) s)])))
               (or (ints a clip) ffi/null)
               (or overlay ffi/null) (or tp ffi/null)
               out (int out-w) (int out-h) (int rx) (int ry) (int rw) (int rh) bgp))))

(defn gpu?
  "Do renders go to the GPU (Metal on macOS; ALMAZ_GPU=0 turns it off)?"
  [] (= 1 (gpu-active*)))

(defn set-gpu!
  "Render on the GPU when there is one (true), or always on the CPU."
  [on?] (gpu-set-enabled* (if on? 1 0)))

;; ---- full-image composite ---------------------------------------------------
(defn composite-into!
  "Write the composite of base + layers into `dst` (an image the same size)."
  [dst base layers]
  (with-open [a (ffi/confined-arena)]
    (composite-into* dst base (write-layers a layers) (int (count layers)))))

;; ---- tiles -------------------------------------------------------------------
(defn tile-extract!
  "The composite of base + layers over `tile` {:x :y :w :h}, as planar floats
   into `dst`."
  [base layers {:keys [x y w h]} dst]
  (with-open [a (ffi/confined-arena)]
    (tile-extract* base (write-layers a layers) (int (count layers))
                   (int x) (int y) (int w) (int h) dst)))

(defn tile-write!
  "Crossfade the planar float tile `src` into `dst` at `tile` {:x :y :w :h}
   with ramps {:l :t :r :b}."
  [dst src {:keys [x y w h]} {:keys [l t r b]}]
  (tile-write* dst src (int w) (int h) (int x) (int y)
               (int (or l 0)) (int (or t 0)) (int (or r 0)) (int (or b 0))))

(defn unsharp!
  "Unsharp mask, in place, on a planar tile of `size` px."
  [t size {:keys [sigma amount]}]
  (tile-unsharp* t (int size) (int size) (float sigma) (float amount)))

(defn gauss!
  "Gaussian blur of a planar w x h tile, in place (sigma in px)."
  [t w h sigma]
  (tile-gauss* t (int w) (int h) (float sigma)))

(defn local-contrast!
  "Local contrast (clarity) of a planar w x h tile, in place: RawTherapee's,
   blur radius `sigma` px; a negative `amount` softens."
  [t w h {:keys [sigma amount dark light] :or {dark 1.0 light 1.0}}]
  (tile-local-contrast* t (int w) (int h) (float sigma) (float amount) (float dark) (float light)))

(defn detail!
  "Put back `amount` of the input's fine detail into the output tile."
  [out in size {:keys [sigma amount]}]
  (tile-detail* out in (int size) (int size) (float sigma) (float amount)))

;; ---- lens correction ---------------------------------------------------------
(defn focus!
  "`dst` = `src` (same size) with local contrast `clarity` at blur radius
   `radius` px, then a gaussian blur of sigma `blur` px, over the whole image:
   on the GPU when there is one. True when the GPU did it."
  [dst src {:keys [clarity radius blur]}]
  (let [r (focus* dst src (float (or clarity 0.0)) (float (or radius 80.0)) (float (or blur 0.0)))]
    (when (neg? r) (throw (ex-info "focus: images differ in size" {})))
    (= 1 r)))

(defn lens-warp!
  "Rows [y0, y1) of `dst` from `src` (same size) through an almaz.lens plan
   {:center [x y] :rmax :k [kr kg kb] :vrmax :vig}."
  ([dst src lens-plan] (lens-warp! dst src lens-plan 0 (height dst)))
  ([dst src {[cx cy] :center :keys [rmax k vrmax vig]} y0 y1]
   (with-open [a (ffi/confined-arena)]
     (let [n (count (first k))]
       (lens-warp* dst src (double cx) (double cy) (double rmax) (int n)
                   (floats a (float-array (apply concat k)))
                   (double (or vrmax 1.0)) (if vig (floats a (float-array vig)) ffi/null)
                   (int y0) (int y1))))))

(def ^:private sony-cap 64)

(defn sony-corrections
  "The lens corrections a Sony raw file carries, {:distortion :ca
   :vignetting} (each count-prefixed, as stored; almaz.lens/sony-coeffs), or
   nil."
  [path]
  (with-open [a (ffi/confined-arena)]
    (let [bufs (repeatedly 3 #(ffi/alloc a (* 4 sony-cap)))
          [d c v] bufs]
      (when (pos? (sony-corrections* (ffi/string->ptr a path) d c v (int sony-cap)))
        (let [rd #(vec (for [i (range sony-cap)] (long (ffi/read % :int32 (* 4 i)))))]
          {:distortion (rd d) :ca (rd c) :vignetting (rd v)})))))

;; ---- segmentation ------------------------------------------------------------
(defn seg-input!
  "The composite of base + layers stretched over n x n into `out` (n*n*3
   planar floats), normalized (v - mean) / std per channel (nil: as is)."
  [base layers n {:keys [mean std]} out]
  (with-open [a (ffi/confined-arena)]
    (seg-input* base (write-layers a layers) (int (count layers)) (int n)
                (if mean (floats a (float-array mean)) ffi/null)
                (if std (floats a (float-array std)) ffi/null) out)))

(defn mask-set!
  "Set mask `m` from a w x h map at native `prob` covering the whole image
   (through a sigmoid first when `logits?`), then feather it."
  [m prob w h logits?]
  (mask-set* m prob (int w) (int h) (if logits? 1 0)))

;; ---- estimators --------------------------------------------------------------
(defn estimate
  "{:noise sigma :sharpness score} — see almaz.autopilot."
  [img]
  (with-open [a (ffi/confined-arena)]
    (let [n (ffi/alloc a :float) s (ffi/alloc a :float)]
      (estimate* img n s)
      {:noise (double (ffi/read n :float 0)) :sharpness (double (ffi/read s :float 0))})))

;; ---- metadata ----------------------------------------------------------------
;; AlmzMeta (almazimg.h): the shooting metadata carried from a source file to
;; its exports, as a map {:make :model :lens :taken :exposure :fnumber :focal
;; :iso :focal35 :orientation} with absent fields left out.
(def meta-layout
  (ffi/layout [:struct [[:make [:array :uint8 64]] [:model [:array :uint8 64]]
                        [:lens [:array :uint8 128]] [:taken [:array :uint8 20]]
                        [:exposure :double] [:fnumber :double] [:focal :double]
                        [:iso :int32] [:focal35 :int32] [:orientation :int32]]]))

(def ^:private meta-strings {:make 64 :model 64 :lens 128 :taken 20})
(def ^:private meta-numbers {:exposure :double :fnumber :double :focal :double
                             :iso :int32 :focal35 :int32 :orientation :int32})

(defn read-meta-struct
  "An AlmzMeta struct at `p` as a map (unknown fields left out)."
  [p]
  (let [strs (keep (fn [[k _]]
                     (let [v (ffi/ptr->string (+ p (ffi/field-offset meta-layout k)))]
                       (when (seq v) [k v])))
                   meta-strings)
        nums (keep (fn [[k t]]
                     (let [v (ffi/read p t (ffi/field-offset meta-layout k))]
                       (when (pos? v) [k (if (= t :double) (double v) (long v))])))
                   meta-numbers)]
    (not-empty (into {} (concat strs nums)))))

(defn- write-meta-struct
  "A zeroed AlmzMeta in `arena` filled from map `m`."
  [arena m]
  (let [p (ffi/alloc arena meta-layout)]
    (doseq [[k cap] meta-strings
            :let [v (get m k)]
            :when (seq v)]
      (ffi/write-bytes (+ p (ffi/field-offset meta-layout k)) (subs v 0 (min (count v) (dec cap)))))
    (doseq [[k t] meta-numbers
            :let [v (get m k)]
            :when v]
      (ffi/write p t (if (= t :double) (double v) (int v)) (ffi/field-offset meta-layout k)))
    p))

(defn read-meta
  "The shooting metadata of a JPEG, PNG or TIFF-structured file (TIFF, NEF,
   ARW, DNG, CR2, ...), or nil when it has none."
  [path]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a meta-layout)]
      (when (pos? (meta-read* (ffi/string->ptr a path) p))
        (read-meta-struct p)))))

;; ---- files -------------------------------------------------------------------
(def formats {:png16 1 :png8 2 :jpeg 3 :tiff16 4})

(defn load-image
  "Decode a JPEG / PNG / TIFF into an image handle. Throws on failure."
  [path]
  (with-open [a (ffi/confined-arena)]
    (let [cpath (ffi/string->ptr a path)]
      (call-with-err (str "cannot load " path) #(load* cpath %) non-null?))))

(defn jpeg-bytes->image
  "Decode JPEG bytes at native pointer `p` (`n` bytes)."
  [p n]
  (call-with-err "bad JPEG" #(load-jpeg-mem* p n %) non-null?))

(defn rgb8->image [p w h] (from-rgb8* p (int w) (int h)))

(def profiles
  "Export colour spaces (ALMZ_PROF_* in almazimg.h)."
  {:none 0 :srgb 1 :p3 2 :adobe 3})

(defn- save-args
  "The writer's arguments after the layer array, in `arena`."
  [a {:keys [quality compression profile xf size] fmt :format}]
  [(if xf (doubles* a xf) ffi/null) (int (or (first size) 0)) (int (or (second size) 0))
   (int (formats fmt)) (int (or quality 90)) (int (or compression -1)) (int (profiles (or profile :srgb)))])

(defn save-image!
  "Write the full-resolution composite to `path` as `format` (see `formats`),
   with `meta` (read-meta's map) embedded as EXIF. :quality is JPEG's 1..100,
   :compression zlib's level for PNG or TIFF's codec (0 none, 1 LZW,
   2 Deflate; default the format's), :profile the colour space (`profiles`,
   default :srgb). The file is written to `path`.part and renamed when
   complete: a large export takes seconds, and anything opening `path`
   meanwhile would read a truncated image. :xf and :size [w h] give the
   output's geometry (output -> source pixels; default the image as is)."
  [base layers {:keys [path meta] :as opts}]
  (let [part (str path ".part")]
    (with-open [a (ffi/confined-arena)]
      (let [larr (write-layers a layers)
            cpath (ffi/string->ptr a part)
            mp (if meta (write-meta-struct a meta) ffi/null)
            [xf w h fmt q c prof] (save-args a opts)]
        (try
          (call-with-err (str "cannot write " path)
                         #(save* base larr (int (count layers)) xf w h cpath fmt q c prof mp %)
                         zero?)
          (catch Exception e
            (let [f (java.io.File. part)] (when (.isFile f) (.delete f)))
            (throw e)))))
    (when-not (.renameTo (java.io.File. part) (java.io.File. path))
      (throw (ex-info (str "cannot write " path) {:path path})))
    path))

(defn estimate-size
  "About how many bytes save-image! would write with `opts` (its options,
   :path aside): a few bands of rows encoded and scaled up."
  [base layers {:keys [meta] :as opts}]
  (with-open [a (ffi/confined-arena)]
    (let [larr (write-layers a layers)
          mp (if meta (write-meta-struct a meta) ffi/null)
          [xf w h fmt q c prof] (save-args a opts)]
      (call-with-err "cannot estimate the export size"
                     #(save-estimate* base larr (int (count layers)) xf w h fmt q c prof mp %)
                     (complement neg?)))))

;; ---- repair ------------------------------------------------------------------
(defn inpaint!
  "Fill the hole (native bytes, 1 = hole) of a planar RGB float w x h image
   at native `src` into `out` (almazimg.h almz_inpaint). Throws when it
   can't."
  [src w h hole seed out]
  (when-not (zero? (inpaint* src (int w) (int h) hole (int seed) out))
    (throw (ex-info "repair failed (area too small or out of memory)" {:w w :h h}))))

(defn inpaint
  "inpaint! over Clojure arrays: planar RGB floats and a hole byte-array;
   returns the filled float-array."
  [^floats src w h ^bytes hole {:keys [seed] :or {seed 1}}]
  (let [n (* 3 w h)
        sp (ffi/alloc (* 4 n)) hp (ffi/alloc (max 1 (* w h))) op (ffi/alloc (* 4 n))]
    (try
      (ffi/write-array sp :float src)
      (ffi/write-array hp hole)
      (inpaint! sp w h hp seed op)
      (float-array (ffi/read-array op :float n))
      (finally (ffi/free sp) (ffi/free hp) (ffi/free op)))))

;; ---- picking -----------------------------------------------------------------
(defn sample
  "The mean colour [r g b] (0..1) of base + layers over the (2r+1)^2 box
   around source pixel (x, y) (the edges mirrored)."
  [base layers [x y] r]
  (let [n (inc (* 2 r))
        cnt (* n n)
        p (ffi/alloc (* 4 3 cnt))]
    (try
      (tile-extract! base layers {:x (- (long x) r) :y (- (long y) r) :w n :h n} p)
      (let [v (ffi/read-array p :float (* 3 cnt))]
        (mapv (fn [c] (/ (reduce + (map #(double (aget v (+ (* c cnt) %))) (range cnt))) cnt))
              (range 3)))
      (finally (ffi/free p)))))
