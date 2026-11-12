(ns almaz.state
  "Application state. The domain data (documents and their filter layers)
   is one relatum database in `db*` (almaz.doc); every change to it goes
   through almaz.doc's functions, so its constraints hold at every write. The
   other cells are UI state: what is selected, how it is viewed, the brush,
   and what the worker is doing."
  (:require [glimmer.ratom :as r]
            [almaz.doc :as d]
            [almaz.geom :as g])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defonce db* (r/atom (d/empty-db)))
(defonce current* (r/atom nil))   ; id of the doc being edited
(defonce view* (r/atom {:mode :single :cw 800 :ch 600 :scale 0.1 :ox 0.0 :oy 0.0
                        :zoom :fit :split 0.5}))
;; :mode :pan | :paint | :erase | :pick; :layer the layer whose mask the brush
;; edits; :pick the levels point a click on the image sets (:black :grey :white)
(defonce tool* (r/atom {:mode :pan :layer nil :radius 150.0 :hardness 0.5 :flow 1.0
                        :show-mask true :pick nil}))
;; the sidebar tab (:enhance :color :dimensions); on :dimensions the image
;; pane edits the crop (the whole image shows, the crop as a frame over it)
(defonce tab* (r/atom :enhance))
;; doc id -> undo history (almaz.history), the last `undo-limit` edits
(defonce history* (r/atom {}))
(defonce job* (r/atom nil))       ; {:label :done :total} while the worker is busy
(defonce message* (r/atom "Add images to begin"))
(defonce autopilot* (r/atom false))
(defonce pixels-rev* (r/atom 0))  ; native pixels changed (tiles written, masks painted)
(defonce cursor* (r/atom nil))    ; pointer over the canvas [x y], nil outside
(defonce space* (r/atom false))   ; Space held over the canvas: drag pans
(defonce mask-req* (r/atom nil))   ; {:doc :layer :sel} an automatic mask for the worker
(defonce export-req* (atom nil))  ; {:doc :path :format :quality} for the worker

;; ---- worker wake-up ----------------------------------------------------------
(defonce ^:private wake-q (LinkedBlockingQueue.))

(defn wake!
  "Tell the worker the state changed."
  [] (.offer wake-q :wake))

(defn await-wake
  "Block the worker for up to `ms` or until woken."
  [ms] (.poll wake-q ms TimeUnit/MILLISECONDS))

;; ---- reading -----------------------------------------------------------------
(defn now [] (System/currentTimeMillis))

(defn doc [id] (d/doc @db* id))
(defn current-doc [] (d/doc @db* @current*))
(defn layers [doc-id] (d/layers @db* doc-id))
(defn layer [id] (d/layer @db* id))

(defn editing-crop? [] (= :dimensions @tab*))

(defn display-size
  "The size the image pane shows the doc at (almaz.geom), once known."
  [doc]
  (when (:w doc) (g/display-size doc (editing-crop?))))

;; ---- writing -----------------------------------------------------------------
(defn swap-db!
  "Apply a db -> db function. A write the schema rejects throws and leaves
   the database as it was."
  [f & args]
  (apply swap! db* f args))

(defn swap-db-with!
  "Swap with `f` : db -> [db' extra] and return the `extra` of the attempt
   that won (e.g. the native handles a removal releases)."
  [f & args]
  (let [extra (atom nil)]
    (swap! db* (fn [db] (let [[db' x] (apply f db args)] (reset! extra x) db')))
    @extra))

(defn swap-doc!
  "Update doc `id` (no-op when it is gone)."
  [id f & args]
  (swap! db* (fn [db] (if (d/doc db id) (apply d/update-doc db id f args) db))))

(defn swap-layer!
  "Update layer `id` (no-op when it is gone)."
  [id f & args]
  (swap! db* (fn [db] (if (d/layer db id) (apply d/update-layer db id f args) db))))

(defn edit!
  "A user edit to doc `id` (`f` is db -> db): apply it, stamp the edit time
   (processing waits for edits to settle) and wake the worker."
  [id f & args]
  (swap! db* (fn [db] (if (d/doc db id)
                        (d/update-doc (apply f db args) id assoc :edited-at (now))
                        db)))
  (wake!))

(defn edit-done!
  "edit!, for an edit that's complete in itself (a finished brush stroke):
   nothing more is coming, so processing needn't wait for it to settle."
  [id f & args]
  (swap! db* (fn [db] (if (d/doc db id)
                        (d/update-doc (apply f db args) id assoc :edited-at 0)
                        db)))
  (wake!))

(defn bump-pixels! [] (swap! pixels-rev* inc))

(defonce ^:private last-bump (atom 0))

(defn bump-pixels-throttled!
  "bump-pixels!, at most every `ms` (progress during processing: every
   re-render composites the whole viewport on the UI thread)."
  [ms]
  (let [t (now)]
    (when (> (- t @last-bump) ms)
      (reset! last-bump t)
      (bump-pixels!))))

(defn message! [s] (reset! message* s))
