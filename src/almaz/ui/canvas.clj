(ns almaz.ui.canvas
  "The image pane: a BUI canvas showing the current image's layer stack.

   The pixels come from the native renderer (almaz.pixels/render!) drawing
   into one reusable RGBA buffer at the canvas's raster size, handed to the
   canvas as an image item by pointer. A frame is re-rendered only when what
   it shows changes (view, layers, pixels); the brush cursor and the split
   divider are separate canvas items, so moving the pointer costs nothing.

   Modes: single (after), split (before | after at a draggable divider) and
   side by side (before and after panes in sync). Pointer: drag pans, wheel
   zooms about the pointer (eased: see almaz.view's smooth zoom), double click
   toggles fit / 100%; with a mask tool active, drag paints or erases the
   layer's mask, and holding Space drags to pan instead.

   The view works in display coordinates (the oriented, cropped picture,
   almaz.geom); the renderer gets the screen -> source mapping, and brush
   strokes and picks are mapped back to source pixels. While the crop is
   edited (the Dimensions tab) the whole image shows, the crop as a frame
   with handles over it."
  (:require [almaz.actions :as act]
            [almaz.doc :as d]
            [almaz.engine :as eng]
            [almaz.geom :as g]
            [almaz.pixels :as px]
            [almaz.state :as s]
            [almaz.tables :as tables]
            [almaz.view :as v]
            [glimmer.core :as ui]
            [jolt.ffi :as ffi])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def bg [36 36 36 255])
(def mask-tint [1.0 0.25 0.2 0.45])

;; ---- the frame buffer ----------------------------------------------------------
(defonce ^:private frame (atom {:ptr nil :w 0 :h 0 :key nil :version 0}))

(defn- buffer! [w h]
  (let [{:keys [ptr] fw :w fh :h} @frame]
    (if (and ptr (= [w h] [fw fh]))
      ptr
      (let [p (ffi/alloc (* 4 (max 1 w) (max 1 h)))]
        ;; the canvas copied the old pixels when they were set, so it can go
        (when ptr (ffi/free ptr))
        (swap! frame assoc :ptr p :w w :h h :key nil)
        p))))

(defn- display
  "What to draw: the decoded image and its layers, or the embedded preview
   while the full image decodes. :geom is the doc's orientation and crop
   (nil for the preview, shown as it is)."
  [doc layers editing?]
  (cond
    (:base doc) {:img (:base doc) :layers (eng/layer-refs doc layers tables/opts)
                 :geom (select-keys doc [:w :h :orient :crop-x :crop-y :crop-w :crop-h]) :editing? editing?}
    (:preview doc) {:img (:preview doc) :layers [] :preview? true
                    :w (px/width (:preview doc)) :h (px/height (:preview doc))}))

(defn- pane-xf
  "Screen -> source pixels for a pane at canvas x offset dx."
  [{:keys [scale ox oy]} dx geom editing?]
  (let [screen->display [(/ 1.0 scale) 0.0 (/ (- (+ ox dx)) scale) 0.0 (/ 1.0 scale) (/ (- oy) scale)]]
    (if geom (g/compose (g/display->source geom editing?) screen->display) screen->display)))

(defn- clip [geom editing?]
  (when (and geom (not editing?)) (g/crop-rect geom)))

(defn- tool-mask
  "The mask the brush edits, when a mask tool is active."
  [layers tool]
  (when (#{:paint :erase} (:mode tool))
    (some #(when (= (:id %) (:layer tool)) (:mask %)) layers)))

(defonce ^:private stroking (atom false)) ; a brush stroke is under way

(defn- overlay-mask
  "The mask tinted over the image: the brushed layer's while painting it. A
   repair's only during the stroke (after it, the repaired picture shows)."
  [layers tool]
  (when (:show-mask tool)
    (let [l (some #(when (= (:id %) (:layer tool)) %) layers)]
      (when (or (not (d/mask-input? l)) @stroking)
        (tool-mask layers tool)))))

(defn- draw!
  [{:keys [img layers w h preview? geom editing?]} view overlay]
  (let [{:keys [cw ch mode]} view
        out (buffer! cw ch)
        view (if preview? (v/fit view w h) view)
        pane (v/pane-width view)
        go (fn [ls rect dx ov]
             (px/render! img ls {:xf (pane-xf view dx geom editing?) :clip (clip geom editing?)
                                 :overlay ov :tint mask-tint
                                 :out out :out-w cw :out-h ch :rect rect :bg bg}))]
    (case (if editing? :single mode)
      :single (go layers [0 0 cw ch] 0 overlay)
      :split (let [sx (long (v/split-x view))]
               (go [] [0 0 sx ch] 0 nil)
               (go layers [sx 0 (- cw sx) ch] 0 overlay))
      :side (do (go [] [0 0 pane ch] 0 nil)
                (go layers [pane 0 (- cw pane) ch] pane overlay)))))

(defonce ^:private blank (delay (px/new-image 1 1)))

(defn- clear!
  "Fill the frame with the background colour: render a 1x1 image placed
   off-screen, so every pixel is outside it."
  [{:keys [cw ch]}]
  (px/render! @blank [] {:ox -10 :oy -10 :scale 1.0 :out (buffer! cw ch)
                         :out-w cw :out-h ch :bg bg}))

(defn frame-for
  "The rendered frame for the current state: {:ptr :w :h :version}.
   Re-renders only when the inputs changed."
  [{:keys [doc layers view tool pixels-rev editing?]}]
  (let [d (display doc layers editing?)
        overlay (overlay-mask layers tool)
        k [(:id doc) (some-> d :img) (:layers d) (:geom d) editing? (dissoc view :zoom) overlay @stroking pixels-rev]]
    (when (not= k (:key @frame))
      (if d (draw! d view overlay) (clear! view))
      (swap! frame #(-> % (assoc :key k) (update :version inc))))
    @frame))

;; ---- overlay items -------------------------------------------------------------
(defn- label-item [id x y text]
  {:id id :type :text :coords [x y] :text text :fill [1 1 1 0.85] :anchor 4})

(defn- compare-items
  "Divider and Before/After labels for the split and side-by-side modes."
  [{:keys [ch mode] :as view}]
  (case mode
    :split (let [x (v/split-x view)]
             [{:id 2 :type :line :coords [x 0 x ch] :fill [1 1 1 0.9] :width 2}
              (label-item 3 12 18 "Before")
              (label-item 4 (+ 12 x) 18 "After")])
    :side (let [x (v/pane-width view)]
            [{:id 2 :type :line :coords [x 0 x ch] :fill [0.1 0.1 0.1 1] :width 2}
             (label-item 3 12 18 "Before")
             (label-item 4 (+ x 12) 18 "After")])
    []))

(defn- brush-item [view tool [x y]]
  (let [r (* (:radius tool) (:scale view))]
    {:id 5 :type :oval :coords [(- x r) (- y r) (+ x r) (+ y r)] :width 1.5
     :outline (if (= :erase (:mode tool)) [0.4 0.8 1 0.9] [1 1 1 0.9])}))

(defn- status-text [{:keys [cw ch]} doc]
  (let [msg (cond (nil? doc) "Add images to start  (RAW, JPEG, PNG, TIFF)"
                  (= :error (:status doc)) (str "Cannot open " (:name doc) ": " (:error doc))
                  (nil? (:base doc)) (str "Decoding " (:name doc) "…"))]
    (when msg
      {:id 6 :type :text :anchor 0 :text msg :fill [1 1 1 0.7]
       :coords [(/ cw 2.0) (if doc (- ch 24.0) (/ ch 2.0))]})))

;; ---- crop editor -----------------------------------------------------------------
(def ^:private handle-px "Grab distance of a crop handle, screen px." 10)

(defn- crop-items
  "The crop frame: shade outside it, its outline, thirds and handles."
  [doc {:keys [cw ch] :as view}]
  (let [[x y w h] (g/display-crop doc)
        [x0 y0] (v/->screen view x y)
        [x1 y1] (v/->screen view (+ x w) (+ y h))
        shade [0 0 0 0.55]
        rect (fn [id a b c e] {:id id :type :rect :coords [a b c e] :fill shade :width 0})
        line (fn [id a b c e] {:id id :type :line :coords [a b c e] :fill [1 1 1 0.35] :width 1})
        tx (fn [f] (+ x0 (* f (- x1 x0)))) ty (fn [f] (+ y0 (* f (- y1 y0))))
        hs 5
        handles (for [[i [hx hy]] (map-indexed vector [[x0 y0] [(tx 0.5) y0] [x1 y0] [x1 (ty 0.5)]
                                                        [x1 y1] [(tx 0.5) y1] [x0 y1] [x0 (ty 0.5)]])]
                  {:id (+ 40 i) :type :rect :coords [(- hx hs) (- hy hs) (+ hx hs) (+ hy hs)]
                   :fill [1 1 1 0.95] :outline [0 0 0 0.6] :width 1})]
    (into [(rect 20 0 0 cw y0) (rect 21 0 y1 cw ch) (rect 22 0 y0 x0 y1) (rect 23 x1 y0 cw y1)
           {:id 24 :type :rect :coords [x0 y0 x1 y1] :outline [1 1 1 0.95] :width 1.5}
           (line 25 (tx (/ 1 3.0)) y0 (tx (/ 1 3.0)) y1) (line 26 (tx (/ 2 3.0)) y0 (tx (/ 2 3.0)) y1)
           (line 27 x0 (ty (/ 1 3.0)) x1 (ty (/ 1 3.0))) (line 28 x0 (ty (/ 2 3.0)) x1 (ty (/ 2 3.0)))]
          handles)))

(defn- overlay-items [doc view tool cursor editing?]
  (cond-> (if (and doc (not editing?)) (compare-items view) [])
    (and editing? (:base doc)) (into (crop-items doc view))
    (and cursor (:base doc) (#{:paint :erase} (:mode tool)) (not @s/space*)) (conj (brush-item view tool cursor))
    (status-text view doc) (conj (status-text view doc))))

;; ---- input ---------------------------------------------------------------------
(defonce ^:private drag (atom nil))

(defn- pane-xy
  "Canvas -> pane coordinates (the right pane in side-by-side mode)."
  [view x y]
  (let [pw (v/pane-width view)]
    (if (and (= :side (:mode view)) (>= x pw) (not (s/editing-crop?))) [(- x pw) y] [x y])))

(defn- ->source
  "Pane coordinates -> source pixels of the current doc."
  [view doc px* py*]
  (g/apply-xf (g/display->source doc (s/editing-crop?)) (v/->image view px* py*)))

(defn- stroke! [mask tool [x0 y0] [x1 y1]]
  (px/stroke! mask [x0 y0] [x1 y1] {:radius (:radius tool) :hardness (:hardness tool)
                                :flow (:flow tool) :erase? (= :erase (:mode tool))})
  (s/bump-pixels!))

;; ---- smooth zoom ---------------------------------------------------------------
;; Wheel steps set {:target scale :x :y (the anchor, pane coords)}; a daemon
;; thread eases the view towards it on the UI thread, one frame per tick,
;; until it settles.
(defonce ^:private zoom-goal (atom nil))
(defonce ^:private zoom-wake (LinkedBlockingQueue.))
(def ^:private ease-ms
  "Time constant of the easing: each `ease-ms` covers ~63% of what's left,
   however long frames take to draw."
  45.0)
(def ^:private frame-ms 8)

(defn- zoom-frame!
  "Advance the zoom by `dt-ms` of easing; true while there is more to do."
  [dt-ms]
  (let [p (promise)
        k (- 1.0 (Math/exp (- (/ (min dt-ms 100.0) ease-ms))))]
    (ui/on-gui (fn []
                 (deliver p (if-let [{:keys [target x y] :as goal} @zoom-goal]
                              (let [vw (swap! s/view* v/zoom-toward target [x y] k)]
                                (if (v/settled? vw target)
                                  (do (compare-and-set! zoom-goal goal nil) false)
                                  true))
                              false))))
    (deref p 500 false)))

(defn- stop-zoom! [] (reset! zoom-goal nil))

(defonce ^:private zoomer
  (delay (doto (Thread. (fn []
                          (loop []
                            (.take zoom-wake)
                            (try
                              (loop [t (- (System/nanoTime) (* frame-ms 1000000))]
                                (let [now (System/nanoTime)]
                                  (when (zoom-frame! (/ (- now t) 1e6))
                                    (Thread/sleep frame-ms)
                                    (recur now))))
                              (catch Throwable e
                                (stop-zoom!)
                                (binding [*out* *err*] (println "almaz: zoom failed:" (ex-message e)))))
                            (recur))))
           (.setDaemon true)
           (.start))))

(defn on-press [x y]
  (stop-zoom!)
  (let [view @s/view* tool @s/tool* doc (s/current-doc)
        [px* py*] (pane-xy view x y)
        ready? (and (:base doc) (not @s/space*))
        mask (when ready? (tool-mask (s/layers (:id doc)) tool))
        editing? (s/editing-crop?)
        crop (when (and ready? editing?) (g/display-crop doc))
        handle (when crop (g/handle-at crop (v/->image view px* py*) (/ handle-px (:scale view))))]
    (cond
      handle (reset! drag {:kind :crop :handle handle :rect crop :start (v/->image view px* py*)})
      (and ready? (= :pick (:mode tool))) (act/pick! (->source view doc px* py*))
      (and (not editing?) (v/on-split? view x)) (reset! drag {:kind :split})
      mask (let [p (->source view doc px* py*)]
             (act/begin-stroke! (:layer tool))
             (reset! stroking true)
             (stroke! mask tool p p)
             (reset! drag {:kind :paint :mask mask :last p}))
      :else (reset! drag {:kind :pan :x x :y y}))))

(defn on-motion [x y]
  (reset! s/cursor* [x y])
  (when-let [{:keys [kind] :as dr} @drag]
    (let [view @s/view*]
      (case kind
        :pan (do (swap! s/view* v/pan (- x (:x dr)) (- y (:y dr)))
                 (swap! drag assoc :x x :y y))
        :split (swap! s/view* assoc :split (min 0.95 (max 0.05 (/ x (double (:cw view))))))
        :paint (let [[px* py*] (pane-xy view x y)
                     p (->source view (s/current-doc) px* py*)]
                 (stroke! (:mask dr) @s/tool* (:last dr) p)
                 (swap! drag assoc :last p))
        :crop (let [doc (s/current-doc)
                    [ix iy] (v/->image view x y)
                    [sx sy] (:start dr)]
                (act/set-display-crop!
                 (g/drag-crop (:rect dr) (:handle dr) [(- ix sx) (- iy sy)]
                              (g/display-size doc true) (g/constraint-aspect doc))))))))

(defn on-release [_ _]
  (reset! stroking false)
  (when (= :paint (:kind @drag))
    (let [tool @s/tool*]
      (when-let [id @s/current*]
        (s/edit-done! id d/bump-mask (:layer tool)))))
  (reset! drag nil))

(def ^:private shift 1)

(defn on-wheel
  "Zoom about the pointer. Shift + wheel is how the kit reports a sideways
   scroll (a diagonal trackpad swipe sends both), which doesn't zoom."
  [delta mods]
  (when (zero? (bit-and (long mods) shift))
    (let [view @s/view*
          [x y] (or @s/cursor* [(/ (:cw view) 2.0) (/ (:ch view) 2.0)])
          [px* py*] (pane-xy view x y)]
      (swap! zoom-goal (fn [g] {:target (v/wheel-target (or (:target g) (:scale view)) (double delta))
                                :x px* :y py*}))
      @zoomer
      (.offer zoom-wake :zoom))))

(defn- space? [k] (= "space" k))
(def ^:private ctrl "The kit's Ctrl bit, which macOS's Command sets too." 2)

(defn on-key
  "Space: pan while held; R: the Repair brush. (Keys reach the image pane
   under the pointer; the app-wide shortcuts are almaz.ui.shortcuts'.)"
  [k mods]
  (let [k (str k) mods (long (or mods 0))
        cmd? (pos? (bit-and mods ctrl))]
    (cond
      (space? k) (reset! s/space* true)
      (and (not cmd?) (#{"r" "R"} k)) (act/repair-tool!))))
(defn on-keyrelease [k _] (when (space? k) (reset! s/space* false)))

(defn on-double [_ _]
  (stop-zoom!)
  (when-let [[w h] (s/display-size (s/current-doc))]
    (if (= :fit (:zoom @s/view*))
      (swap! s/view* v/set-scale 1.0)
      (swap! s/view* v/fit w h))))

(defn on-configure [w h]
  (let [w (long w) h (long h)]
    (when (and (pos? w) (pos? h))
      (swap! s/view* assoc :cw w :ch h)
      (when-let [[dw dh] (s/display-size (s/current-doc))]
        (when (= :fit (:zoom @s/view*))
          (swap! s/view* v/fit dw dh))))))

;; ---- component -----------------------------------------------------------------
(defn image-view []
  (let [doc (s/current-doc)
        layers (when doc (s/layers (:id doc)))
        view @s/view*
        tool @s/tool*
        rev @s/pixels-rev*
        cursor @s/cursor*
        editing? (= :dimensions @s/tab*)
        {:keys [ptr w h version]} (frame-for {:doc doc :layers layers :view view :tool tool :pixels-rev rev
                                              :editing? editing?})]
    [:canvas {:stretch true :events 1
              :on-configure on-configure :on-press on-press :on-motion on-motion
              :on-release on-release :on-wheel on-wheel :on-double on-double
              :on-key on-key :on-keyrelease on-keyrelease
              :on-leave #(reset! s/cursor* nil)
              :canvas-items (into [{:id 1 :type :image :coords [0 0 w h] :rgba ptr
                                    :iw w :ih h :fit 0 :version version}]
                                  (overlay-items doc view tool cursor editing?))}]))
