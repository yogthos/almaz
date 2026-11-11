(ns almaz.ui.sidebar
  "The right-hand panel: histogram, navigator, autopilot, then three tabs:
   Enhance (denoise, sharpen), Color (the colour adjustments) and Dimensions
   (orientation and crop). Each filter has one panel, switched on and off by
   the checkbox in its header; changing a setting switches it on."
  (:require [almaz.actions :as act]
            [almaz.autopilot :as ap]
            [almaz.color :as c]
            [almaz.doc :as d]
            [almaz.engine :as eng]
            [almaz.geom :as g]
            [almaz.pixels :as px]
            [almaz.state :as s]
            [almaz.tables :as tables]
            [almaz.ui.units :refer [px]]
            [almaz.view :as v]
            [almaz.loading :as load]
            [glimmer.ratom :as r]
            [jolt.ffi :as ffi]))

(def width 340)

(defn- refs
  "The doc's stack (or the layers below `kind`) as the UI draws it."
  ([doc] (refs doc nil))
  ([doc below-kind]
   (let [ls (s/layers (:id doc))]
     (eng/layer-refs doc (if below-kind (take-while #(not= below-kind (:kind %)) ls) ls) tables/opts))))

;; ---- navigator -----------------------------------------------------------------
(defonce ^:private nav (atom {:w 300 :h 180 :ptr nil :key nil :version 0}))

(defn- nav-frame [doc rev editing?]
  (let [{:keys [w h]} @nav
        rs (when doc (refs doc))
        k [(:id doc) (:base doc) rs (select-keys doc [:orient :crop-x :crop-y :crop-w :crop-h]) editing? rev w h]]
    (when (and (:base doc) (not= k (:key @nav)))
      (let [{:keys [ptr] pw :pw ph :ph} @nav
            p (if (and ptr (= [pw ph] [w h])) ptr (do (when ptr (ffi/free ptr)) (ffi/alloc (* 4 w h))))]
        (load/render-fit! {:out p :w w :h h :bg [30 30 30 255] :doc doc :editing? editing?} (:base doc) rs)
        (swap! nav #(-> % (assoc :ptr p :pw w :ph h :key k) (update :version inc)))))
    @nav))

(defn- nav-jump! [x y]
  (when-let [doc (s/current-doc)]
    (when (:base doc)
      (let [{:keys [s ox oy]} (load/fit-geometry (:w @nav) (:h @nav) (:base doc) doc (s/editing-crop?))
            ix (/ (- x ox) s) iy (/ (- y oy) s)
            view @s/view*
            [cx cy] (v/->image view (/ (v/pane-width view) 2.0) (/ (:ch view) 2.0))]
        (swap! s/view* v/pan (* (- cx ix) (:scale view)) (* (- cy iy) (:scale view)))))))

(defonce ^:private nav-drag (atom false))

(defn navigator []
  (let [doc (s/current-doc)
        view @s/view*
        editing? (= :dimensions @s/tab*)
        {:keys [ptr w h version]} (nav-frame doc @s/pixels-rev* editing?)]
    [:canvas {:height-units 9 :events 1
              :on-configure (fn [cw ch] (swap! nav assoc :w (long cw) :h (long ch) :key nil))
              :on-press (fn [x y] (reset! nav-drag true) (nav-jump! x y))
              :on-motion (fn [x y] (when @nav-drag (nav-jump! x y)))
              :on-release (fn [_ _] (reset! nav-drag false))
              :canvas-items
              (if (and ptr (:base doc))
                (let [{:keys [s ox oy dw dh]} (load/fit-geometry w h (:base doc) doc editing?)
                      [x y rw rh] (v/visible-rect view dw dh)]
                  [{:id 1 :type :image :coords [0 0 w h] :rgba ptr :iw w :ih h :fit 0 :version version}
                   {:id 2 :type :rect :coords [(+ ox (* s x)) (+ oy (* s y))
                                               (+ ox (* s (+ x rw))) (+ oy (* s (+ y rh)))]
                    :outline [1 1 1 0.9] :width 1.5}])
                [{:id 3 :type :text :coords [(/ w 2.0) (/ h 2.0)] :anchor 0
                  :text "No image" :fill [1 1 1 0.4]}])}]))

;; ---- histogram -----------------------------------------------------------------
;; Of what the image pane shows (the full stack, adjustments included, within
;; the crop), redrawn only when that changes.
(defn- hist-cell [] (atom {:w 300 :h 100 :ptr nil :key nil :version 0}))

(defn- hist-frame
  "Draw doc's histogram (of `rs` over base, within `clip`) into `cell`."
  [cell doc rs clip rev]
  (let [{:keys [w h]} @cell
        img (or (:base doc) (:preview doc))
        k [(:id doc) img rs clip rev w h]]
    (when (and img (not= k (:key @cell)))
      (let [{:keys [ptr] pw :pw ph :ph} @cell
            p (if (and ptr (= [pw ph] [w h])) ptr (do (when ptr (ffi/free ptr)) (ffi/alloc (* 4 w h))))]
        (px/draw-histogram! img rs {:out p :w w :h h :bg [30 30 30 255] :clip clip})
        (swap! cell #(-> % (assoc :ptr p :pw w :ph h :key k) (update :version inc)))))
    @cell))

(defonce ^:private hist (hist-cell))

(defn histogram []
  (let [doc (s/current-doc)
        editing? (= :dimensions @s/tab*)
        rs (when (:base doc) (refs doc))
        clip (when (and (:base doc) (not editing?)) (g/crop-rect doc))
        {:keys [ptr w h version]} (hist-frame hist doc rs clip @s/pixels-rev*)]
    [:canvas {:height-units 4 :events 1
              :on-configure (fn [cw ch] (swap! hist assoc :w (long cw) :h (long ch) :key nil))
              :canvas-items (if (and ptr (or (:base doc) (:preview doc)))
                              [{:id 1 :type :image :coords [0 0 w h] :rgba ptr :iw w :ih h :fit 0 :version version}]
                              [])}]))

;; ---- controls ------------------------------------------------------------------
(defn- pct [x] (Math/round (* 100.0 (double x))))
(defn- index-of [xs x] (or (first (keep-indexed #(when (= x %2) %1) xs)) 0))

;; A thin gradient under a slider shows which way it moves the colour (the
;; strips share the panel width, learnt from their configure events).
(defonce ^:private strip-w (r/atom 300))
(defonce ^:private strip-cache (atom {}))
(def ^:private strip-n 96)

(defn- strip-bytes
  "RGBA8 of f(t) for t in 0..1 (f gives sRGB), cached by `key`."
  [key f]
  (or (@strip-cache key)
      (let [a (byte-array (* 4 strip-n))]
        (dotimes [i strip-n]
          (let [rgb (f (/ i (double (dec strip-n))))]
            (dotimes [ch 3] (aset a (+ (* 4 i) ch) (unchecked-byte (Math/round (* 255.0 (max 0.0 (min 1.0 (nth rgb ch))))))))
            (aset a (+ (* 4 i) 3) (unchecked-byte 255))))
        (swap! strip-cache assoc key a)
        a)))

(defn- lerp-rgb [a b t] (mapv #(+ %1 (* t (- %2 %1))) a b))

(defn- two-way
  "a at the left, grey in the middle, b at the right."
  [a b]
  (fn [t] (if (< t 0.5) (lerp-rgb a [0.5 0.5 0.5] (* 2 t)) (lerp-rgb [0.5 0.5 0.5] b (- (* 2 t) 1)))))

(defn- strip [key f]
  (let [h (px 3)
        w @strip-w]
    [:canvas {:reqheight h
              :on-configure (fn [cw _] (let [cw (long cw)] (when (and (pos? cw) (not= cw @strip-w)) (reset! strip-w cw))))
              :canvas-items [{:id 1 :type :image :coords [0 0 w h] :rgba (strip-bytes key f)
                              :iw strip-n :ih 1 :fit 0}]}]))

(defn- setting
  "A slider for colour setting `k` of `kind` (its range from almaz.color),
   in percent, with an optional gradient [key f]."
  [kind layer k label & [[skey f]]]
  (let [[_ lo hi] (get-in c/settings [kind k])]
    [:vbox {:key k :spacing 0}
     [:slider {:label label :min (* 100 lo) :max (* 100 hi) :integer 1 :value (pct (get layer k))
               :on-change #(act/set-param! kind k (/ (double %) 100.0))}]
     (when skey (strip skey f))]))

;; ---- mask ------------------------------------------------------------------------
;; A section of its filter's panel, under a rule: Off / Paint / Erase, and the
;; brush and mask settings while painting or erasing that layer.
(defn- mask-tools [layer tool]
  (let [lid (:id layer)
        active (when (= lid (:layer tool)) (:mode tool))]
    [:vbox {:key :mask :spacing 4}
     [:separator {:key :rule}]
     [:hbox {:key :mode :spacing 4}
      [:label {:text "Mask" :bold 1 :weight 0 :reqwidth (px 52)}]
      [:enum {:items ["Off" "Paint" "Erase"] :weight 1
              :value (case active :paint 1 :erase 2 0)
              :on-change #(act/set-tool! ([:pan :paint :erase] (long %)) lid)}]]
     (when active
       [:vbox {:key :brush :spacing 4}
        [:slider {:label "Brush size" :min 4 :max 1500 :integer 1 :value (long (:radius tool))
                  :on-change #(swap! s/tool* assoc :radius (double %))}]
        [:slider {:label "Hardness" :min 0 :max 100 :integer 1 :value (pct (:hardness tool))
                  :on-change #(swap! s/tool* assoc :hardness (/ (double %) 100.0))}]
        [:slider {:label "Feather" :min 0 :max 200 :integer 1 :value (long (:feather layer))
                  :on-change #(act/set-feather! lid (double %))}]
        [:hbox {:align 1}
         [:button {:text "Fill" :on-click #(act/mask-fill! lid 1.0)}]
         [:button {:text "Clear" :on-click #(act/mask-fill! lid 0.0)}]
         [:button {:text "Invert" :on-click #(act/mask-invert! lid)}]]
        [:checkbox {:label "Show mask while painting" :value (if (:show-mask tool) 1 0)
                    :on-change #(swap! s/tool* assoc :show-mask (pos? %))}]])]))

;; ---- enhance ---------------------------------------------------------------------
(defn- layer-status [doc layer sig job]
  (cond
    (not (:enabled layer)) nil
    (nil? (:base doc)) "Waiting for the image"
    (= sig (:done-sig layer)) "Up to date"
    job (str "Processing " (or (:done job) 0) "/" (or (:total job) "…"))
    :else "Queued"))

(defn- denoise-settings [layer]
  [:slider {:label "Recover detail" :min 0 :max 100 :integer 1
            :value (pct (:detail layer))
            :on-change #(act/set-param! :denoise :detail (/ (double %) 100.0))}])

(defn- sharpen-settings [{:keys [db doc layer]}]
  (let [{:keys [model amount radius]} layer]
    [:vbox {:spacing 4}
     (when (and (= model :ai) (ap/noisy? (d/estimate doc)) (:id layer) (not (d/denoised-below? db (:id layer))))
       [:label {:text "Noisy image: AI deblur amplifies noise. Turn on Denoise." :text-color [1 0.75 0.3 1]}])
     [:label {:text "Model"}]
     [:enum {:items ["AI deblur" "Unsharp mask"] :value (if (= model :classic) 1 0)
             :on-change #(act/set-param! :sharpen :model (if (= 1 (long %)) :classic :ai))}]
     (when (= model :classic)
       [:vbox {:spacing 4}
        [:slider {:label "Radius" :min 0.5 :max 8 :precision 1 :value radius
                  :on-change #(act/set-param! :sharpen :radius (double %))}]
        [:slider {:label "Amount" :min 0 :max 300 :integer 1 :value (pct amount)
                  :on-change #(act/set-param! :sharpen :amount (/ (double %) 100.0))}]])]))

;; ---- repair ----------------------------------------------------------------------
;; The Repair brush paints the layer's mask; the worker fills what it covers
;; from around it. Restore paints it away again.
(defn- repair-settings [layer tool]
  (let [lid (:id layer)
        active (when (and lid (= lid (:layer tool))) (:mode tool))]
    [:vbox {:spacing 4}
     [:enum {:key :mode :items ["Off" "Repair" "Restore"]
             :value (case active :paint 1 :erase 2 0)
             :on-change #(case (long %)
                           0 (act/set-tool! :pan nil)
                           1 (act/repair-tool!)
                           2 (do (act/repair-tool!)
                                 (act/set-tool! :erase (:id (d/layer-of @s/db* @s/current* :repair)))))}]
     [:slider {:key :size :label "Brush size" :min 4 :max 1500 :integer 1 :value (long (:radius tool))
               :on-change #(swap! s/tool* assoc :radius (double %))}]
     [:slider {:key :hardness :label "Hardness" :min 0 :max 100 :integer 1 :value (pct (:hardness tool))
               :on-change #(swap! s/tool* assoc :hardness (/ (double %) 100.0))}]
     [:hbox {:key :clear :align 1}
      [:button {:text "Clear repairs" :enabled (if lid 1 0) :on-click #(act/mask-fill! lid 0.0)}]]
     [:label {:key :hint :text "Paint over what to remove (R). Restore paints a repair away."
              :text-color [1 1 1 0.55]}]]))

;; ---- colour ----------------------------------------------------------------------
(defn- tone-slider [layer k label]
  [:slider {:key k :label label :min -100 :max 100 :integer 1 :value (pct (get layer k))
            :on-change #(act/set-param! :light k (/ (double %) 100.0))}])

(defn- light-settings [layer]
  [:vbox {:spacing 4}
   [:slider {:label "Exposure" :min -5 :max 5 :precision 2 :value (:exposure layer)
             :on-change #(act/set-param! :light :exposure (double %))}]
   (tone-slider layer :contrast "Contrast")
   (tone-slider layer :highlights "Highlights")
   (tone-slider layer :shadows "Shadows")
   (tone-slider layer :whites "Whites")
   (tone-slider layer :blacks "Blacks")
   (tone-slider layer :saturation "Saturation")
   [:button {:text "Auto exposure" :on-click act/auto-exposure!}]])

(def ^:private hsl-strip-base [0.8 0.3 0.25])

(defn- hsl-settings [layer]
  [:vbox {:spacing 4}
   (setting :hsl layer :hs-hue "Hue"
            [[:hsl :hue] #((c/transform :hsl {:hs-hue (- (* 2 %) 1)}) hsl-strip-base)])
   (setting :hsl layer :hs-sat "Saturation"
            [[:hsl :sat] #((c/transform :hsl {:hs-sat (- (* 2 %) 1)}) hsl-strip-base)])
   (setting :hsl layer :hs-vib "Vibrance"
            [[:hsl :vib] #((c/transform :hsl {:hs-sat (* 0.5 (- (* 2 %) 1))}) [0.6 0.5 0.45])])])

;; selective colour: a row of swatches picks the band the sliders edit
(defonce ^:private band* (r/atom :red))

(defn- band-picker [layer]
  (let [n (count c/bands)
        w @strip-w h (px 22)
        cell (/ w (double n))
        cur @band*]
    [:canvas {:reqheight h :events 1
              :on-configure (fn [cw _] (let [cw (long cw)] (when (and (pos? cw) (not= cw @strip-w)) (reset! strip-w cw))))
              :on-press (fn [x _] (reset! band* (nth c/bands (max 0 (min (dec n) (long (/ x cell)))))))
              :canvas-items
              (vec (for [[i b] (map-indexed vector c/bands)
                         :let [x0 (+ (* i cell) 2) x1 (- (* (inc i) cell) 2)
                               edited? (some #(not (zero? (double (get layer (c/sel-key b %) 0.0)))) [:hue :sat :bri])]]
                     {:id (inc i) :type :roundrect :coords [x0 3 x1 (- h 3)] :radius 4
                      :fill (conj (c/band-colours b) 1.0)
                      :outline (cond (= b cur) [1 1 1 1] edited? [1 1 1 0.45] :else [0 0 0 0.4])
                      :width (if (= b cur) 2.5 1)}))}]))

(defn- band-hue-shift [rgb t] ((c/transform :hsl {:hs-hue (* 0.25 (- (* 2 t) 1))}) rgb))

(defn- selective-settings [layer]
  (let [b @band*
        col (c/band-colours b)
        k #(c/sel-key b %)]
    [:vbox {:spacing 4}
     [band-picker layer]
     [:label {:text (str (get {:red "Reds" :orange "Oranges" :yellow "Yellows" :green "Greens" :cyan "Cyans"
                               :blue "Blues" :purple "Purples" :magenta "Magentas"} b))}]
     (setting :selective layer (k :hue) "Hue" [[:sel b :hue] #(band-hue-shift col %)])
     (setting :selective layer (k :sat) "Saturation"
              [[:sel b :sat] #((c/transform :hsl {:hs-sat (- (* 2 %) 1)}) col)])
     (setting :selective layer (k :bri) "Brightness"
              [[:sel b :bri] #(lerp-rgb (mapv (partial * 0.3) col) (lerp-rgb col [1 1 1] 0.5) %)])]))

(defonce ^:private range* (r/atom :midtones))

(defn- balance-settings [layer]
  (let [rng @range*
        k #(c/bal-key rng %)]
    [:vbox {:spacing 4}
     [:dropdown {:items ["Shadows" "Midtones" "Highlights"] :value (index-of c/ranges rng)
                 :on-change #(reset! range* (nth c/ranges (long %)))}]
     (setting :balance layer (k :cr) "Cyan / Red" [[:bal :cr] (two-way [0.2 0.75 0.75] [0.85 0.25 0.25])])
     (setting :balance layer (k :mg) "Magenta / Green" [[:bal :mg] (two-way [0.8 0.3 0.75] [0.3 0.75 0.3])])
     (setting :balance layer (k :yb) "Yellow / Blue" [[:bal :yb] (two-way [0.85 0.75 0.2] [0.3 0.5 0.9])])
     (setting :balance layer (k :sat) "Saturation" [[:bal :sat] #(lerp-rgb [0.45 0.45 0.45] [0.85 0.25 0.2] %)])
     (setting :balance layer (k :bri) "Brightness" [[:bal :bri] #(lerp-rgb [0.15 0.15 0.15] [0.85 0.85 0.85] %)])]))

;; levels: a curve over the histogram of what levels receives, with handles
;; for the black, grey and white points of the chosen channel
(defonce ^:private channel* (r/atom :rgb))
(defonce ^:private levels-drag (atom nil))
(def ^:private levels-pad 8)

(defn- levels-points [layer ch]
  (mapv #(double (get layer (c/lv-key ch %))) [:black :grey :white]))

(defonce ^:private levels-data (atom {:key nil :hist nil}))
(defonce ^:private levels-size (r/atom [300 150]))

(defn- levels-histogram
  "256-bin histograms of what levels receives (the stack below it, within
   the crop), cached by what they depend on."
  [doc rs rev]
  (let [k [(:id doc) (:base doc) rs (g/crop-rect doc) rev]]
    (when (and (:base doc) (not= k (:key @levels-data)))
      (reset! levels-data {:key k :hist (px/histogram (:base doc) rs (g/crop-rect doc))}))
    (:hist @levels-data)))

(defn- levels-graph [doc layer]
  (let [ch @channel*
        [w h] @levels-size
        rs (when (:base doc) (refs doc :levels))
        hist (when (:base doc) (levels-histogram doc rs @s/pixels-rev*))
        [b gr wt] (levels-points layer ch)
        pad levels-pad
        iw (- w (* 2 pad)) ih (- h (* 2 pad))
        sx (fn [v] (+ pad (* v iw))) sy (fn [v] (+ pad (* (- 1 v) ih)))
        pts (mapv (fn [i] (let [x (/ i 64.0)] [(sx x) (sy (c/level b gr wt x))])) (range 65))
        colour (case ch :r [1 0.45 0.45 1] :g [0.45 1 0.5 1] :b [0.5 0.65 1 1] [0.9 0.9 0.9 1])
        dot (fn [id x y] {:id id :type :oval :coords [(- x 5) (- y 5) (+ x 5) (+ y 5)]
                          :fill [0.85 0.85 0.85 1] :outline [0 0 0 0.7] :width 1})
        ->value (fn [x] (/ (- x pad) (double iw)))
        ;; the histogram as one filled shape, square-rooted counts (darktable
        ;; draws levels' log-ish too, so small tones stay visible), ignoring the
        ;; clipped end bins when scaling
        bins (when hist (get hist (case ch :r :r :g :g :b :b :l)))
        top (when bins (max 1.0 (Math/sqrt (double (apply max (subvec bins 1 255))))))
        shape (when bins
                (vec (concat [(sx 0) (sy 0)]
                             (mapcat (fn [i] [(sx (/ (+ i 0.5) 256.0))
                                              (sy (min 1.0 (/ (Math/sqrt (double (nth bins i))) top)))])
                                     (range 256))
                             [(sx 1) (sy 0)])))]
    [:canvas {:height-units 8 :events 1
              :on-configure (fn [cw chh] (let [v [(long cw) (long chh)]] (when (not= v @levels-size) (reset! levels-size v))))
              :on-press (fn [x y]
                          (let [hs [[:black (sx b) (sy 0)] [:grey (sx gr) (sy 0.5)] [:white (sx wt) (sy 1)]]
                                [which] (first (sort-by (fn [[_ hx hy]] (Math/hypot (- x hx) (- y hy))) hs))]
                            (reset! levels-drag which)
                            (act/set-level! ch which (->value x))))
              :on-motion (fn [x _] (when-let [which @levels-drag] (act/set-level! ch which (->value x))))
              :on-release (fn [_ _] (reset! levels-drag nil))
              :canvas-items
              (cond-> [{:id 1 :type :rect :coords [pad pad (- w pad) (- h pad)] :fill [0.12 0.12 0.12 1]
                        :outline [1 1 1 0.15] :width 1}]
                shape (conj {:id 2 :type :polygon :coords shape :fill (assoc colour 3 0.28) :width 0})
                true (into (for [i [1 2 3]]
                             {:id (+ 10 i) :type :line :coords [(sx (/ i 4.0)) pad (sx (/ i 4.0)) (- h pad)]
                              :fill [1 1 1 0.1] :width 1}))
                ;; the curve as segments (a line item draws only its first one)
                true (into (map-indexed (fn [i [[x0 y0] [x1 y1]]]
                                          {:id (+ 100 i) :type :line :coords [x0 y0 x1 y1] :fill colour :width 1.5})
                                        (partition 2 1 pts)))
                true (conj (dot 21 (sx b) (sy 0)) (dot 22 (sx gr) (sy 0.5)) (dot 23 (sx wt) (sy 1))))}]))

(defn- levels-settings [doc layer tool]
  (let [ch @channel*
        [b gr wt] (levels-points layer ch)
        picking (when (= :pick (:mode tool)) (:pick tool))
        pick-button (fn [which label]
                      [:button {:key which :icon "EYEDROPPER" :text label
                                :description (str "Click the image to set the " label " point")
                                :on-click #(if (= picking which) (act/set-tool! :pan nil) (act/start-pick! which))}])]
    ;; the picking hint comes last: a child that appears mid-list can leave the
    ;; ones after it out of order
    [:vbox {:spacing 4}
     [:hbox {:key :pickers :spacing 4}
      [:dropdown {:items ["RGB" "Red" "Green" "Blue"] :value (index-of c/channels ch) :weight 1
                  :on-change #(reset! channel* (nth c/channels (long %)))}]
      (pick-button :black "Black") (pick-button :grey "Grey") (pick-button :white "White")]
     [levels-graph doc layer]
     [:slider {:key :black :label "Black" :min 0 :max 100 :precision 1 :value (* 100 b)
               :on-change #(act/set-level! ch :black (/ (double %) 100.0))}]
     [:slider {:key :grey :label "Grey" :min 0 :max 100 :precision 1 :value (* 100 gr)
               :on-change #(act/set-level! ch :grey (/ (double %) 100.0))}]
     [:slider {:key :white :label "White" :min 0 :max 100 :precision 1 :value (* 100 wt)
               :on-change #(act/set-level! ch :white (/ (double %) 100.0))}]
     (when picking [:label {:key :hint :text (str "Click the image to pick the " (name picking) " point")
                            :text-color [1 0.85 0.4 1]}])]))

(defn- vignette-settings [layer]
  [:vbox {:spacing 4}
   (setting :vignette layer :vig-exposure "Exposure" [[:vig :exp] #(lerp-rgb [0.05 0.05 0.05] [0.95 0.95 0.95] %)])
   (setting :vignette layer :vig-black "Black Point")
   (setting :vignette layer :vig-softness "Softness")])

(defn- bw-settings [layer]
  [:vbox {:spacing 4}
   (setting :bw layer :bw-red "Red" [[:bw :r] #(lerp-rgb [0.3 0.3 0.3] [0.9 0.25 0.2] %)])
   (setting :bw layer :bw-green "Green" [[:bw :g] #(lerp-rgb [0.3 0.3 0.3] [0.3 0.8 0.3] %)])
   (setting :bw layer :bw-blue "Blue" [[:bw :b] #(lerp-rgb [0.3 0.3 0.3] [0.3 0.5 0.95] %)])
   (setting :bw layer :bw-tone "Tone" [[:bw :tone] (two-way [0.45 0.55 0.7] [0.75 0.6 0.4])])
   (setting :bw layer :bw-intensity "Intensity")])

;; ---- panels ----------------------------------------------------------------------
(defn- layer-or-defaults
  "The doc's `kind` layer, or how it would start (off) when it has none."
  [db doc kind]
  (or (when doc (d/layer-of db (:id doc) kind))
      (merge (get-in d/kinds [kind :params]) {:kind kind :enabled false :opacity (d/default-opacity kind)})))

(defn- filter-panel
  [{:keys [db doc tool sigs job stale-id] :as ctx} kind open?]
  (let [layer (layer-or-defaults db doc kind)
        lid (:id layer)
        idx (when lid (d/layer-index db lid))]
    [:panel {:key kind :text (get-in d/kinds [kind :label]) :collapsed (if open? 0 1)}
     [:checkbox {:header true :label "" :value (if (:enabled layer) 1 0) :enabled (if doc 1 0)
                 :on-change #(act/set-enabled! kind (pos? %))}]
     [:vbox {:spacing 4 :enabled (if doc 1 0)}
      (when-not (d/live? layer)
        [:label {:key :status
                 :text (or (when lid (layer-status doc layer (when idx (nth sigs idx)) (when (= lid stale-id) job)))
                           "Off")}])
      [:slider {:key :strength :label "Strength" :min 0 :max 100 :integer 1 :value (pct (:opacity layer))
                :on-change #(act/set-layer! kind :opacity (/ (double %) 100.0))}]
      (case kind
        :denoise (denoise-settings layer)
        :sharpen (sharpen-settings (assoc ctx :layer layer))
        :repair (repair-settings layer tool)
        :light (light-settings layer)
        :levels (levels-settings doc layer tool)
        :balance (balance-settings layer)
        :hsl (hsl-settings layer)
        :selective (selective-settings layer)
        :bw (bw-settings layer)
        :vignette (vignette-settings layer))
      [:hbox {:key :reset :align 1}
       [:button {:text "Reset" :enabled (if lid 1 0) :on-click #(act/reset-kind! kind)}]]
      ;; a repair's mask is the repair itself (its own panel paints it)
      (when (and lid (:mask layer) (not (d/mask-input? layer))) (mask-tools layer tool))]]))

(defn- panels-ctx []
  (let [db @s/db*
        doc (s/current-doc)
        stale (when doc (d/next-stale db (:id doc)))]
    {:db db :doc doc :tool @s/tool* :job @s/job*
     :sigs (when doc (d/signatures db (:id doc)))
     :stale-id (when (and doc stale) (:id (nth (d/layers db (:id doc)) stale)))}))

(defn enhance-page []
  (let [ctx (panels-ctx)]
    [:vbox {:spacing 4 :padding 2}
     (for [k (d/kinds-in :enhance)] (filter-panel ctx k true))]))

(defn color-page []
  (let [ctx (panels-ctx)]
    [:vbox {:spacing 4 :padding 2}
     (for [k (d/kinds-in :color)] (filter-panel ctx k false))]))

;; ---- dimensions ------------------------------------------------------------------
(defn dimensions-page []
  (let [doc (s/current-doc)
        ok (if (:base doc) 1 0)
        [dw dh] (when (:w doc) (g/display-size doc false))
        [bw bh] (when (:w doc) (g/display-size doc true))
        ci (or (first (keep-indexed (fn [i x] (when (= (:key x) (:constrain doc)) i)) g/constraints)) 0)]
    [:vbox {:spacing 6 :padding 4}
     [:label {:text "Orientation" :bold 1}]
     [:hbox {:spacing 4}
      [:button {:icon "LOOP_BACK" :text "Rotate left" :enabled ok :on-click #(act/rotate! -1)}]
      [:button {:icon "LOOP_FORWARDS" :text "Rotate right" :enabled ok :on-click #(act/rotate! 1)}]]
     [:hbox {:spacing 4}
      [:button {:text "Flip horizontal" :enabled ok :on-click act/flip-h!}]
      [:button {:text "Flip vertical" :enabled ok :on-click act/flip-v!}]]
     [:separator]
     [:label {:text "Crop" :bold 1}]
     [:dropdown {:items (mapv :label g/constraints) :value ci :enabled ok
                 :on-change #(act/set-constrain! (:key (nth g/constraints (long %))))}]
     (case (:constrain doc)
       :custom-size
       [:hbox {:spacing 4}
        [:number {:label "W" :min g/min-crop :max (or bw 100000) :integer 1 :value (or dw 0) :enabled ok
                  :on-change #(act/set-crop-size! % dh)}]
        [:number {:label "H" :min g/min-crop :max (or bh 100000) :integer 1 :value (or dh 0) :enabled ok
                  :on-change #(act/set-crop-size! dw %)}]]
       :custom-ratio
       [:hbox {:spacing 4}
        [:number {:label "W" :min 0.1 :max 100 :precision 2 :value (:ratio-w doc) :enabled ok
                  :on-change #(act/set-ratio! % (:ratio-h doc))}]
        [:number {:label "H" :min 0.1 :max 100 :precision 2 :value (:ratio-h doc) :enabled ok
                  :on-change #(act/set-ratio! (:ratio-w doc) %)}]]
       nil)
     (when dw [:label {:text (str "Result: " dw " × " dh " px")}])
     [:button {:text "Reset crop" :enabled (if (and (:base doc) (g/cropped? doc)) 1 0) :on-click act/reset-crop!}]
     [:label {:text "Drag the frame or its handles on the image." :text-color [1 1 1 0.55]}]]))

;; ---- layout ----------------------------------------------------------------------
(def ^:private tabs [:enhance :color :dimensions])

(defn autopilot-row []
  (let [doc (s/current-doc)]
    [:vbox {:spacing 4}
     [:hbox {}
      [:toggle {:label "Autopilot" :value (if @s/autopilot* 1 0)
                :on-change #(act/set-autopilot! (pos? %))}]]
     (when-let [est (d/estimate doc)]
       [:label {:text (ap/describe est)}])]))

(defn sidebar []
  [:vbox {:weight 0 :reqwidth (px width) :padding (px 8) :spacing (px 6)}
   [histogram]
   [navigator]
   [autopilot-row]
   [:tabs {:items ["Enhance" "Color" "Dimensions"] :stretch true
           :current (index-of tabs @s/tab*)
           :on-change #(act/set-tab! (nth tabs (long %)))}
    [:scroll {} [enhance-page]]
    [:scroll {} [color-page]]
    [:scroll {} [dimensions-page]]]])
