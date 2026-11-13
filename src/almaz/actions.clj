(ns almaz.actions
  "User commands: what buttons, sliders and the canvas do to the database.
   They run on the UI thread; slow work is left to the worker (almaz.worker)."
  (:require [clojure.string :as str]
            [glimmer-bui.ffi :as bk]
            [almaz.autopilot :as ap]
            [almaz.color :as c]
            [almaz.doc :as d]
            [almaz.engine :as eng]
            [almaz.export :as ex]
            [almaz.files :as files]
            [almaz.geom :as g]
            [almaz.history :as h]
            [almaz.lifecycle :as life]
            [almaz.pixels :as px]
            [almaz.raw :as raw]
            [almaz.state :as s]
            [almaz.view :as v]))

;; ---- images ----------------------------------------------------------------
(defn select-doc!
  "Make doc `id` the one being edited. The previous one gives back its
   full-resolution pixels (they are re-decoded when it is selected again)."
  [id]
  (let [prev @s/current*]
    (when (not= id prev)
      (when (s/doc prev)
        (apply life/retire! (s/swap-db-with! life/evict prev)))
      (reset! s/current* id)
      (swap! s/tool* assoc :mode :pan :layer nil)
      (swap! s/view* assoc :zoom :fit)
      (when-let [[w h] (s/display-size (s/doc id))]
        (swap! s/view* v/fit w h))
      (s/wake!))))

(defn add-images!
  "Add image files to the filmstrip; the first one added opens when nothing is."
  [paths]
  (let [known (set (map :path (d/docs @s/db*)))
        fresh (->> paths (filter files/supported?) (remove known) distinct)]
    (doseq [p fresh]
      (s/swap-db! d/add-doc {:path p :raw? (raw/raw-file? p)}))
    (when (and (nil? @s/current*) (seq (d/docs @s/db*)))
      (select-doc! (:id (first (d/docs @s/db*)))))
    (when (and (seq paths) (empty? fresh))
      (s/message! "No new supported images"))
    (s/wake!)))

(defn open-images!
  "Files the desktop handed over (dropped on the window, or opened with
   Almaz): add the images among them, folders' too, and show the first."
  [paths]
  (let [imgs (files/expand paths)]
    (if (empty? imgs)
      (s/message! "Nothing to add: no supported images")
      (let [known (set (map :path (d/docs @s/db*)))
            fresh (remove known imgs)]
        (when (seq fresh) (add-images! fresh))
        (when-let [dc (first (filter #(= (first imgs) (:path %)) (d/docs @s/db*)))]
          (select-doc! (:id dc)))
        (s/message! (str "Added " (count fresh) " image" (when (not= 1 (count fresh)) "s")
                         (when (< (count fresh) (count imgs))
                           (str " (" (- (count imgs) (count fresh)) " already open)"))))))))

(def ^:private open-filters
  [["Images" "*.nef *.nrw *.arw *.cr2 *.cr3 *.raf *.orf *.rw2 *.dng *.pef *.srw *.jpg *.jpeg *.png *.tif *.tiff"]
   ["All files" "*"]])

(defn open-dialog! []
  (when-let [paths (bk/file-dialog {:title "Add images" :mode :open :multiple true
                                    :filters open-filters})]
    (add-images! paths)))

(declare forget-history!)

(defn remove-doc! [id]
  (when (s/doc id)
    (forget-history! id)
    (apply life/retire! (s/swap-db-with! (fn [db] [(d/remove-doc db id) (life/doc-natives db id)])))
    (when (= id @s/current*)
      (reset! s/current* nil)
      (when-let [n (first (d/docs @s/db*))] (select-doc! (:id n))))))

;; ---- undo history ----------------------------------------------------------
;; Every user edit first records the doc's state (almaz.history); edits with
;; the same key in quick succession (a slider drag) are one step. Mask edits
;; also keep a copy of the mask they change.
(defn- cur-id [] @s/current*)

(def undo-limit "Undo steps kept per image." 10)

(defn- drop-entries!
  "Free the mask copies of history entries no longer kept."
  [entries]
  (apply life/retire! (for [e entries [_ m] (:masks e)] [:mask m])))

(defn- entry-now
  "The current doc's state as a history entry, with copies of the masks of
   layers `lids`."
  [id lids]
  {:state (h/snapshot @s/db* id)
   :masks (into {} (keep (fn [lid] (when-let [m (:mask (s/layer lid))] [lid (px/mask-copy m)]))) lids)})

(defn- record!
  "Remember the current doc's state before an edit. `key`: same-key edits
   soon after are one step (nil: always a step of its own); `mask-lids`: the
   layers whose masks the edit paints."
  ([key] (record! key nil))
  ([key mask-lids]
   (when-let [id (cur-id)]
     (let [[hist dropped] (h/record (get @s/history* id h/empty) (entry-now id mask-lids)
                                    {:key key :at (s/now)} undo-limit)]
       (swap! s/history* assoc id hist)
       (drop-entries! dropped)))))

(defn- forget-history! [id]
  (let [{:keys [undo redo]} (get @s/history* id)]
    (swap! s/history* dissoc id)
    (drop-entries! (concat undo redo))))

(defn can-undo? [] (h/can-undo? (get @s/history* (cur-id))))
(defn can-redo? [] (h/can-redo? (get @s/history* (cur-id))))

;; ---- layers ----------------------------------------------------------------
;; One layer per kind, made the first time it is switched on or edited.

(defn- kind-layer!
  "The current doc's layer of `kind`, created (with its mask) if it has none."
  [kind]
  (when-let [id (cur-id)]
    (or (:id (d/layer-of @s/db* id kind))
        (do (s/edit! id (fn [db] (first (d/ensure-layer db id kind))))
            (life/ensure-masks! id)
            (:id (d/layer-of @s/db* id kind))))))

(defn set-enabled!
  "Switch the current doc's `kind` filter on or off."
  [kind on?]
  (when-let [id (cur-id)]
    (record! nil)
    (if on?
      (let [lid (kind-layer! kind)] (s/edit! id d/update-layer lid assoc :enabled true))
      (when-let [l (d/layer-of @s/db* id kind)]
        (s/edit! id d/update-layer (:id l) assoc :enabled false)
        (when (= (:id l) (:layer @s/tool*)) (swap! s/tool* assoc :mode :pan :layer nil))))))

(defn- edit-kind!
  "Apply `f` (db lid -> db) to the `kind` layer, switching it on: touching a
   filter's settings means wanting to see it. `key`: the undo step (see
   record!)."
  [kind key f]
  (record! key)
  (when-let [lid (kind-layer! kind)]
    (s/edit! (cur-id) (fn [db] (f (d/update-layer db lid assoc :enabled true) lid)))))

(defn set-param! [kind k val] (edit-kind! kind [:param kind k] (fn [db lid] (d/set-param db lid k val))))
(defn set-layer! [kind k val] (edit-kind! kind [:layer kind k] (fn [db lid] (d/update-layer db lid assoc k val))))
(defn reset-kind!
  "Put a filter's settings back to their defaults."
  [kind]
  (when-let [l (some-> (cur-id) (#(d/layer-of @s/db* % kind)))]
    (record! nil)
    (s/edit! (cur-id) d/reset-params (:id l))))

(defn auto-exposure!
  "Set the light layer's exposure so the image under it reaches white (its
   brightest 1%), the brightening raw converters otherwise apply unasked."
  []
  (when-let [{:keys [id base] :as dc} (s/current-doc)]
    (when base
      (let [below (take-while #(not= :light (:kind %)) (s/layers id))
            {:keys [l]} (px/histogram base (eng/layer-refs dc below))]
        (set-param! :light :exposure (ap/auto-exposure l))))))

(defn set-level!
  "Move a levels point (almaz.color/set-level)."
  [ch which v]
  (edit-kind! :levels [:level ch which]
              (fn [db lid]
                        (let [l (d/layer db lid)
                              p (c/set-level (second (d/colour l)) ch which v)]
                          (d/update-layer db lid merge p)))))

(defn set-curve!
  "Set curves channel `ch`'s points (almaz.color). `step` names the undo
   step: a drag's edits share one."
  [ch pts step]
  (edit-kind! :curves [:curve ch step]
              (fn [db lid] (d/set-param db lid (c/cv-key ch) (c/format-points pts)))))

(defn start-pick!
  "The next click on the image sets levels' `which` point (:black :grey
   :white), or with :curve, puts a point on curves channel `ch` at the
   picked tone."
  ([which] (start-pick! which nil))
  ([which ch]
   (swap! s/tool* assoc :mode :pick :pick which :pick-ch ch :layer nil)))

(defn- tone-of
  "Where colour `rgb` sits on curves channel `ch` (0..1)."
  [ch [r g b]]
  (case ch
    :r r :g g :b b
    :rgb (/ (+ r g b) 3.0)
    :l (c/lin->srgb (+ (* 0.2126 (c/srgb->lin r)) (* 0.7152 (c/srgb->lin g)) (* 0.0722 (c/srgb->lin b))))))

(defn- pick-curve!
  "Put a point on the curves channel at the tone the image has at source
   pixel p (what curves receives there), on the curve as it is."
  [dc p ch]
  (let [below (take-while #(not= :curves (:kind %)) (s/layers (:id dc)))
        rgb (px/sample (:base dc) (eng/layer-refs dc below) p 2)
        x (tone-of ch rgb)
        key (c/cv-key ch)
        pts (c/parse-points (get (d/layer-of @s/db* (:id dc) :curves) key ""))
        [pts' _] (c/add-point pts x ((c/curve-fn pts) x))]
    (set-curve! ch pts' nil)))

(defn pick!
  "Set the levels point being picked from the image at source pixel p: the
   colour levels receives there (the stack below it)."
  [p]
  (when-let [{:keys [id base] :as dc} (s/current-doc)]
    (let [{which :pick ch :pick-ch} @s/tool*]
      (swap! s/tool* assoc :mode :pan :pick nil :pick-ch nil)
      (if (= :curve which)
        (when base (pick-curve! dc p ch))
      (when (and base which)
        (let [below (take-while #(not= :levels (:kind %)) (s/layers id))
              rgb (px/sample base (eng/layer-refs dc below) p 2)]
          (edit-kind! :levels nil
                      (fn [db lid]
                                (d/update-layer db lid merge
                                                (c/pick-level (second (d/colour (d/layer db lid))) which rgb))))))))))

(defn- with-mask!
  "Apply `f` to layer `lid`'s mask, an undo step (same-key ones merge)."
  [lid key f]
  (when-let [id (cur-id)]
    (when-let [m (:mask (s/layer lid))]
      (record! key [lid])
      (f m)
      (s/edit! id d/bump-mask lid)
      (s/bump-pixels!))))

(defn mask-fill! [lid value] (with-mask! lid nil #(px/mask-fill! % value)))
(defn mask-invert! [lid] (with-mask! lid nil px/mask-invert!))

(defn set-feather! [lid sigma]
  (with-mask! lid [:feather lid] #(px/mask-feather! % sigma))
  (when-let [id (cur-id)] (s/edit! id d/update-layer lid assoc :feather (double sigma))))

(defn begin-stroke!
  "A brush stroke on layer `lid`'s mask starts: an undo step. A painted mask
   is a custom one."
  [lid]
  (record! nil [lid])
  (when-let [id (cur-id)]
    (when (not= :custom (:mask-sel (s/layer lid)))
      (s/edit! id d/update-layer lid assoc :mask-sel :custom))))

(defn select-mask!
  "Set layer `lid`'s mask to a selection (almaz.segment/selections): :all
   fills it, :custom keeps it, the others are found by a model on the
   worker. The brush comes out on the layer, the mask shown, to see the
   selection and refine it. An undo step (but for :custom, which changes no
   pixels)."
  [lid sel]
  (when-let [id (cur-id)]
    (when-not (= :custom sel) (record! nil [lid]))
    (s/edit! id d/update-layer lid assoc :mask-sel sel)
    (reset! s/mask-req* nil)
    (swap! s/tool* assoc :mode :paint :layer lid :pick nil :show-mask true)
    (case sel
      :all (when-let [m (:mask (s/layer lid))]
             (px/mask-fill! m 1.0)
             (s/edit! id d/bump-mask lid)
             (s/bump-pixels!))
      :custom nil
      (do (reset! s/mask-req* {:doc id :layer lid :sel sel})
          (s/wake!)))))

(defn set-tool! [mode lid]
  (swap! s/tool* assoc :mode mode :layer (when (not= mode :pan) lid) :pick nil))

;; ---- autopilot -------------------------------------------------------------
(defn set-autopilot! [on?]
  (reset! s/autopilot* on?)
  (when-let [dc (s/current-doc)]
    (when (and on? (empty? (s/layers (:id dc))) (d/estimate dc))
      (s/edit! (:id dc) life/with-autopilot (:id dc))
      (life/ensure-masks! (:id dc)))))

;; ---- view ------------------------------------------------------------------
(defn- doc-size [] (s/display-size (s/current-doc)))

(defn- refit!
  "Fit the image again if the view is fitted (what it shows changed size)."
  []
  (when (= :fit (:zoom @s/view*))
    (when-let [[w h] (doc-size)] (swap! s/view* v/fit w h))))

(defn set-tab!
  "Show sidebar tab `tab`; the Dimensions tab shows the whole image to crop."
  [tab]
  (when (not= tab @s/tab*)
    (reset! s/tab* tab)
    (swap! s/tool* assoc :mode :pan :layer nil :pick nil)
    (swap! s/view* assoc :zoom :fit)
    (refit!)))

;; ---- geometry --------------------------------------------------------------
(defn- edit-geom!
  "Apply `f` (doc -> doc) to the current doc's orientation and crop, an undo
   step of `key`."
  [key f & args]
  (when-let [id (cur-id)]
    (when (:w (s/doc id))
      (record! key)
      (s/edit! id (fn [db] (d/update-doc db id #(apply f % args))))
      (refit!))))

(defn rotate! [dir] (edit-geom! nil (fn [dc] (update dc :orient #(g/rotate (or % 0) dir)))))
(defn flip-h! [] (edit-geom! nil (fn [dc] (update dc :orient #(g/flip-h (or % 0))))))
(defn flip-v! [] (edit-geom! nil (fn [dc] (update dc :orient #(g/flip-v (or % 0))))))
(defn set-constrain! [k] (edit-geom! nil g/constrain k))
(defn set-crop-size! [w h] (edit-geom! [:crop-size] g/with-crop-size [(max g/min-crop (long w)) (max g/min-crop (long h))]))
(defn set-ratio!
  "The custom aspect ratio's w and h."
  [rw rh]
  (edit-geom! [:ratio] (fn [dc] (g/constrain (assoc dc :ratio-w (max 0.01 (double rw)) :ratio-h (max 0.01 (double rh)))
                                    :custom-ratio))))
(defn reset-crop! []
  (edit-geom! nil (fn [dc] (assoc dc :crop-x nil :crop-y nil :crop-w nil :crop-h nil :constrain :original))))
(defn set-display-crop!
  "Set the crop from the crop editor's rect (display coordinates)."
  [rect]
  (when-let [id (cur-id)]
    (record! [:crop-drag])
    (s/edit! id (fn [db] (d/update-doc db id #(g/with-display-crop % rect))))))

;; ---- undo / redo -----------------------------------------------------------
(defn- apply-entry!
  "Put the doc back to history entry `e`: its state, and its masks."
  [id {:keys [state masks]}]
  ;; an undo is complete in itself: processing needn't wait for more
  (s/edit-done! id (fn [db] (h/restore db id state)))
  (doseq [[lid snap] masks]
    (when-let [m (:mask (s/layer lid))]
      (px/mask-restore! m snap)
      (s/edit-done! id d/bump-mask lid)))
  (when (seq masks) (s/bump-pixels!))
  ;; its mask copies are back in the masks: no longer needed
  (drop-entries! [{:masks masks}])
  (refit!))

(defn- step!
  "Undo (h/undo, the :undo end) or redo (h/redo, the :redo end)."
  [move end done]
  (when-let [id (cur-id)]
    (let [hist (get @s/history* id h/empty)]
      (when-let [e (peek (end hist))]
        (let [[hist' e] (move hist (entry-now id (keys (:masks e))))]
          (swap! s/history* assoc id hist')
          (apply-entry! id e)
          (s/message! done))))))

(defn undo! "Take back the last edit to the current image." [] (step! h/undo :undo "Undone"))
(defn redo! "Do again what undo took back." [] (step! h/redo :redo "Redone"))

;; ---- repair ----------------------------------------------------------------
(defn repair-tool!
  "Brush with the Repair tool: switch Repair on and paint its mask."
  []
  (when (:base (s/current-doc))
    (set-tab! :enhance)
    (when-let [lid (kind-layer! :repair)]
      (when-not (:enabled (s/layer lid))
        (record! nil)
        (s/edit! (cur-id) d/update-layer lid assoc :enabled true))
      (set-tool! :paint lid))))

(defn set-mode! [mode]
  (swap! s/view* assoc :mode mode)
  (refit!))

(defn zoom-fit! []
  (when-let [[w h] (doc-size)] (swap! s/view* v/fit w h)))

(defn zoom-to! [scale] (swap! s/view* v/set-scale scale))

(defn zoom-by!
  "Zoom about the pane centre by `factor` (a keyboard step)."
  [factor]
  (when (doc-size) (swap! s/view* v/zoom-at factor (/ (v/pane-width @s/view*) 2.0) (/ (:ch @s/view*) 2.0))))

;; ---- export ----------------------------------------------------------------
(defn open-export!
  "Show the export panel (settings, size), for an image that has loaded."
  []
  (when (:base (s/current-doc))
    (reset! s/export-open* true)
    (s/wake!)))

(defn close-export! [] (reset! s/export-open* false))

(defn set-export!
  "Change one export setting (almaz.export/defaults' keys)."
  [k v]
  (swap! s/export-settings* assoc k v)
  (s/wake!))

(defn export!
  "Ask where to write the current image with the panel's settings, then queue
   it for the worker."
  []
  (when-let [dc (s/current-doc)]
    (let [settings @s/export-settings*
          ext (ex/ext settings)
          dir (str/replace (:path dc) #"/[^/]*$" "")]
      (when-let [path (first (bk/file-dialog {:title (str "Export " (ex/label settings)) :mode :save
                                              :dir dir :file (ex/file-name (:name dc) settings)
                                              :filters [[(ex/label settings) (str "*." ext)]]}))]
        (let [path (ex/with-ext path settings)]
          (reset! s/export-req* {:doc (:id dc) :path path :opts (ex/save-opts settings)})
          (close-export!)
          (s/message! (if (d/processed? @s/db* (:id dc))
                        (str "Exporting to " path)
                        "Export queued: finishing filters first"))
          (s/wake!))))))
