(ns almaz.worker
  "The background worker: one thread that loads images, runs filter layers,
   makes thumbnails and exports, reading what to do from the database in
   almaz.state.

   Priorities: a pending export of the current image, then loading the
   current image, then (re)processing its first stale layer once edits have
   settled, then the export panel's size estimate, then redrawing its thumbnail when the picture changed without
   processing (light adjustments), then filmstrip thumbnails for images not
   opened yet. A layer job is cancelled as soon as the layer's signature
   changes (an edit underneath it, or the layer itself), it is disabled, or
   another image is selected. Loading and thumbnails live in almaz.loading."
  (:require [glimmer.backend :as b]
            [almaz.doc :as d]
            [almaz.engine :as eng]
            [almaz.geom :as g]
            [almaz.lifecycle :as life]
            [almaz.loading :as load]
            [almaz.models :as models]
            [almaz.pixels :as px]
            [almaz.segment :as seg]
            [almaz.state :as s]
            [almaz.view :as v]))

(def settle-ms "Processing waits this long after the last edit." 400)

;; ---- processing ----------------------------------------------------------------
(defn- live-sig
  "The current signature of layer `lid` of doc `id`, or nil when it is gone,
   disabled, or the doc is no longer the one being edited."
  [id lid]
  (let [db @s/db*]
    (when (= id @s/current*)
      (when-let [i (d/layer-index db lid)]
        (when (:enabled (d/layer db lid))
          (nth (d/signatures db id) i))))))

(defn- layer-output!
  "The layer's output image, created from its input composite the first time
   (so unprocessed tiles look unchanged while processing streams in)."
  [{:keys [base w h]} {lid :id :as layer} below]
  (or (:img layer)
      (let [img (px/new-image w h)]
        (px/composite-into! img base below)
        (s/swap-layer! lid assoc :img img)
        ;; deleted meanwhile: nothing owns it (freed once the job lets go)
        (when-not (= img (:img (s/layer lid))) (life/retire! [:img img]))
        img)))

(defn- mb [n] (quot n 1000000))

(defn- session-for
  "The layer's model session, downloading the model first if it is missing."
  [layer label]
  (when-let [m (eng/model-id layer)]
    (models/ensure-model! m (fn [got total]
                              (reset! s/job* {:label (str "Downloading " label " model ("
                                                          (mb got) "/" (mb (or total 0)) " MB)")
                                              :done got :total total})))
    (reset! s/job* {:label (str "Loading " label " model")})
    (models/session m)))

(defn- report-progress [label]
  (fn [done total]
    (reset! s/job* {:label (str label "…") :done done :total total})
    (if (= done total) (s/bump-pixels!) (s/bump-pixels-throttled! 250))))

(defn- finished!
  "Record a finished run: valid for `sig`; a :partial run (masked-out tiles
   skipped) only for the mask revision it ran with."
  [{:keys [id base]} {:keys [lid sig result mask-rev]}]
  (s/swap-layer! lid assoc :done-sig sig :partial-rev (when (= :partial result) mask-rev))
  (when (d/processed? @s/db* id)
    (load/set-thumb! id base (eng/layer-refs (s/doc id) (s/layers id)) (d/output-signature @s/db* id))))

(defn- focus
  "The source point at the centre of the image pane: tiles start there."
  [dc]
  (let [[dw dh] (or (s/display-size dc) [(:w dc) (:h dc)])]
    (g/apply-xf (g/display->source dc (s/editing-crop?)) (v/focus @s/view* dw dh))))

;; A repair layer's last finished run: {lid {:input :img}}, its input
;; signature and output image then. While both still hold, a new run only
;; redoes where the mask changed since (almaz.engine/repair! :dirty).
(defonce ^:private repaired (atom {}))

(defn- repair-layer!
  "Run a repair, incrementally when only its mask changed since it last
   finished."
  [{:keys [dc layer below out input label cancelled?]}]
  (let [lid (:id layer)
        mask (:mask layer)
        dirty (px/mask-take-dirty! mask)
        incremental? (= {:input input :img out} (get @repaired lid))]
    (when-not incremental? (swap! repaired dissoc lid))
    (let [result (if (and incremental? (nil? dirty))
                   :done
                   (eng/repair! {:base (:base dc) :below below :out out :mask mask
                                 :dirty (when incremental? dirty)
                                 :progress (report-progress label) :cancelled? cancelled?}))]
      (if (= :done result)
        (swap! repaired assoc lid {:input input :img out})
        ;; the next run redoes what this one took on
        (when (and incremental? dirty) (px/mask-add-dirty! mask dirty)))
      result)))

(defn- run-layer!
  "Run the job's tiles; record the result unless it was cancelled."
  [{:keys [dc layer sig below out label] :as job}]
  (let [{:keys [id base w h]} dc
        lid (:id layer)
        t0 (s/now)
        cancelled? #(not= sig (live-sig id lid))
        result (case (:kind layer)
                 :repair (repair-layer! (assoc job :cancelled? cancelled?))
                 :lens (eng/lens! {:base base :below below :out out
                                   :plan (eng/lens-plan @s/db* dc layer)
                                   :progress (report-progress label) :cancelled? cancelled?})
                 :focus (eng/focus! {:base base :below below :out out :layer layer})
                 (eng/process! {:base base :below below :out out :mask (:mask layer)
                                :op (eng/op-for layer (session-for layer label))
                                :focus (focus dc)
                                :progress (report-progress label)
                                :cancelled? cancelled?}))]
    (when (#{:done :partial} result)
      (finished! dc {:lid lid :sig sig :result result :mask-rev (:mask-rev layer)})
      (s/message! (format "%s done in %.1fs" label (/ (- (s/now) t0) 1000.0))))))

(defn- process-layer! [{:keys [id base] :as dc} idx]
  (let [ls (s/layers id)
        layer (nth ls idx)
        below (eng/layer-refs dc (take idx ls))
        out (layer-output! dc layer below)]
    ;; from the first tile written, the old output is no longer valid for any
    ;; signature: a cancelled run must leave the layer stale
    (s/swap-layer! (:id layer) assoc :done-sig nil :partial-rev nil)
    (life/using! (concat [base out (:mask layer)] (map :img below) (map :mask below)))
    (try
      (run-layer! {:dc dc :layer layer :below below :out out
                   :sig (nth (d/signatures @s/db* id) idx)
                   :input (nth (d/input-signatures @s/db* id) idx)
                   :label (get-in d/kinds [(:kind layer) :label])})
      (finally (life/released!)))))

;; ---- automatic masks -----------------------------------------------------------
(defn- seg-input
  "What the segmentation models see: the base, lens-corrected when the lens
   layer is on and done, and the signature naming it."
  [{:keys [id]}]
  (let [db @s/db*
        ls (d/layers db id)
        sigs (d/signatures db id)
        lens (first (keep-indexed (fn [i l] (when (and (= :lens (:kind l)) (:enabled l) (:img l)
                                                       (= (nth sigs i) (:done-sig l)))
                                              [l (nth sigs i)]))
                                  ls))]
    {:layers (if lens [(first lens)] []) :sig (second lens)}))

(defn- mask!
  "Set a layer's mask to an automatic selection (almaz.segment)."
  [{:keys [doc layer sel] :as req} {:keys [base] :as dc}]
  (try
    (let [{:keys [model]} (seg/source sel)
          {:keys [layers sig]} (seg-input dc)
          refs (eng/layer-refs dc layers)
          m (:mask (s/layer layer))]
      (when m
        (life/using! (concat [base m] (map :img layers)))
        (try
          (models/ensure-model! model (fn [got total]
                                        (reset! s/job* {:label (str "Downloading the " (get-in models/registry [model :label])
                                                                    " model (" (mb got) "/" (mb (or total 0)) " MB)")
                                                        :done got :total total})))
          (reset! s/job* {:label (str (seg/describe sel) "…")})
          (let [out (seg/output model [base sig] base refs)]
            ;; a newer request (or the layer gone) supersedes this one
            (when (and (= req @s/mask-req*) (= m (:mask (s/layer layer))))
              (seg/apply! m sel out)
              (s/edit! doc d/bump-mask layer)
              (s/bump-pixels!)))
          (finally (life/released!)))))
    (finally (swap! s/mask-req* #(if (= % req) nil %)))))

;; ---- export --------------------------------------------------------------------
(defn- write-opts
  "save-image!'s options for doc `dc`: `opts` plus the picture's metadata and
   geometry (oriented and cropped)."
  [opts dc]
  (assoc opts :meta (d/exif @s/db* (:id dc))
         :xf (g/display->source dc false) :size (g/display-size dc false)))

(defn- export!
  "Write the doc's picture, oriented and cropped."
  [{:keys [path opts]} {:keys [id base name]}]
  (reset! s/job* {:label (str "Exporting " name)})
  (let [ls (s/layers id)
        dc (s/doc id)]
    (life/using! (concat [base] (map :img ls) (map :mask ls)))
    (try
      (let [t0 (s/now)]
        (px/save-image! base (eng/layer-refs dc ls) (assoc (write-opts opts dc) :path path))
        (s/message! (format "Exported %s (%.1fs)" path (/ (- (s/now) t0) 1000.0))))
      (finally
        (reset! s/export-req* nil)
        (life/released!)))))

(defn- estimate!
  "How big the export panel's settings would make the doc's file."
  [k {:keys [id base]}]
  (let [ls (s/layers id)
        dc (s/doc id)]
    (life/using! (concat [base] (map :img ls) (map :mask ls)))
    (try
      (let [n (px/estimate-size base (eng/layer-refs dc ls) (write-opts (second k) dc))]
        (reset! s/estimate* {:key k :bytes n}))
      (finally (life/released!)))))

;; ---- the loop ------------------------------------------------------------------
(defn- export-task [cur]
  (let [req @s/export-req*]
    (when (and req (= (:doc req) (:id cur)) (= :ready (:status cur)) (d/processed? @s/db* (:id cur)))
      [:export req cur])))

(defn- estimate-task [cur]
  (when (and @s/export-open* (= :ready (:status cur)) (:base cur) (d/processed? @s/db* (:id cur)))
    (let [k (s/estimate-key cur)]
      (when (not= k (:key @s/estimate*))
        [:estimate k cur]))))

(defn- load-task [cur]
  (when (#{:pending :thumb} (:status cur)) [:load cur]))

(defn- mask-task [cur]
  (let [req @s/mask-req*]
    (when (and req (= (:doc req) (:id cur)) (= :ready (:status cur)))
      [:mask req cur])))

(defn- process-task [cur]
  (when-let [i (and (= :ready (:status cur)) (d/next-stale @s/db* (:id cur)))]
    (when (> (- (s/now) (:edited-at cur)) settle-ms)
      [:process cur i])))

(defn- rethumb-task [cur]
  (let [db @s/db*]
    (when (and (= :ready (:status cur)) (:base cur) (d/processed? db (:id cur))
               (> (- (s/now) (:edited-at cur)) settle-ms)
               (not= (d/output-signature db (:id cur)) (:thumb-sig cur)))
      [:rethumb cur])))

(defn- thumb-task []
  (when-let [p (first (filter #(= :pending (:status %)) (d/docs @s/db*)))]
    [:thumb p]))

(defn- pick-task
  "The next job, by priority (see the namespace doc)."
  []
  (let [cur (s/current-doc)]
    (or (when cur (or (export-task cur) (load-task cur) (mask-task cur) (process-task cur) (estimate-task cur) (rethumb-task cur)))
        (thumb-task))))

(defn- run-task! [[kind a b]]
  (case kind
    :export  (export! a b)
    :estimate (estimate! a b)
    :mask    (mask! a b)
    :load    (load/load-doc! a)
    :process (process-layer! a b)
    :rethumb (load/rethumb! a)
    :thumb   (load/thumb-doc! a)))

(defn- failed! [[kind doc-or-req] e]
  (s/message! (str "Error: " (ex-message e)))
  (case kind
    (:load :thumb) (s/swap-doc! (:id doc-or-req) assoc :status :error :error (str (ex-message e)))
    :export (reset! s/export-req* nil)
    :estimate (reset! s/estimate* {:key doc-or-req :bytes nil})  ; don't retry the same
    :mask (reset! s/mask-req* nil)
    nil))

(defn- step! []
  (if-let [task (when @b/loop-running? (pick-task))]
    (try (run-task! task)
         (catch Throwable e (failed! task e)))
    (do (reset! s/job* nil)
        (s/await-wake 150))))

(defonce ^:private thread (atom nil))

(defn start!
  "Start the worker thread (once) and return it. It takes no work until the UI
   loop runs: before that, a change to a reactive cell re-renders
   synchronously on the changing thread, and a render racing the window's
   mount duplicates nodes. It is a daemon: the app ends when its window
   closes, mid-job or not."
  []
  (or @thread
      (reset! thread (doto (Thread. (fn [] (loop [] (step!) (recur))))
                       (.setDaemon true)
                       (.start)))))
