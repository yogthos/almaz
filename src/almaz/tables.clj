(ns almaz.tables
  "Colour tables for the UI, built off the UI thread.

   A colour cube takes tens of milliseconds to build (almaz.color/table),
   and a slider drag asks for a new one at every step. Renders on the UI
   thread use `table`: the table for the settings if it's built, else the
   last one built for that layer (a frame or two behind while dragging) with
   the build started on a background thread, which redraws once it lands.
   Builds coalesce: only the newest settings per layer are built. Exports
   and thumbnails use almaz.color/table directly, always exact."
  (:require [almaz.color :as c]
            [almaz.state :as s]
            [glimmer.core :as ui])
  (:import (java.util.concurrent LinkedBlockingQueue)))

(defonce ^:private shown (atom {}))   ; layer id -> the table last drawn
(defonce ^:private pending (atom {})) ; layer id -> [kind settings] to build
(defonce ^:private wake (LinkedBlockingQueue.))

(defn- build-one!
  "Build layer `lid`'s pending table (unless newer settings replaced it
   meanwhile, they stay pending) and redraw with it."
  [lid [kind p :as req]]
  (swap! pending (fn [m] (if (= req (get m lid)) (dissoc m lid) m)))
  (swap! shown assoc lid (c/table kind p))
  (ui/on-gui s/bump-pixels!))

(defn- build-pending! []
  (loop []
    (when-let [[lid req] (first @pending)]
      (build-one! lid req)
      (recur))))

(defonce ^:private builder
  (delay (doto (Thread. (fn []
                          (loop []
                            (.take wake)
                            (try (build-pending!)
                                 (catch Throwable e
                                   (binding [*out* *err*] (println "almaz: colour table failed:" (ex-message e)))))
                            (recur))))
           (.setDaemon true)
           (.start))))

(defn table
  "The table for layer `l` at settings `p` of `kind`, or the layer's last
   one while that builds (nil before the first)."
  [l kind p]
  (let [t (c/cached kind p)]
    (if (= t ::c/none)
      (do (swap! pending assoc (:id l) [kind p])
          @builder
          (.offer wake :build)
          (get @shown (:id l)))
      (do (swap! shown assoc (:id l) t) t))))

(def opts "layer-refs options for UI renders." {:table table})
