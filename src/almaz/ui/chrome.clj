(ns almaz.ui.chrome
  "Everything around the image: the top bar (file, view modes, export), the
   status bar (progress, size, zoom) and the filmstrip."
  (:require [almaz.actions :as act]
            [almaz.doc :as d]
            [almaz.geom :as g]
            [almaz.state :as s]
            [almaz.strip :as strip]
            [almaz.loading :as load]
            [glimmer.ratom :as r]
            [almaz.ui.shortcuts :as sc]
            [almaz.ui.units :refer [px]]
            [almaz.view :as v]))

(def version "0.2.0")

(defn top-bar []
  (let [doc (s/current-doc)
        {:keys [mode]} @s/view*]
    [:hbox {:padding 6 :spacing 8}
     [:label {:text "Almaz" :bold 1 :weight 0 :font-scale 1.4}]
     [:separator {:weight 0}]
     (when doc [:label {:text (if (:raw doc) "RAW" "RGB") :weight 0 :bold 1}])
     [:label {:text (or (:name doc) "") :weight 0}]
     [:label {:text (or (when doc (d/shot-info (d/exif @s/db* (:id doc)))) "") :weight 1}]
     [:enum {:items ["Single" "Split" "Side by side"] :weight 0
             :value (case mode :split 1 :side 2 0)
             :on-change #(act/set-mode! ([:single :split :side] (long %)))}]
     [:label {:text "" :weight 1}]
     (let [_ @s/history* _ @s/current*]
       [:hbox {:weight 0 :spacing 2}
        [:button {:text "Undo" :icon "LOOP_BACK" :enabled (if (act/can-undo?) 1 0) :on-click act/undo!
                  :description (str "Undo (" (sc/label :undo) ")")}]
        [:button {:text "Redo" :icon "LOOP_FORWARDS" :enabled (if (act/can-redo?) 1 0) :on-click act/redo!
                  :description (str "Redo (" (sc/label :redo) ")")}]])
     [:label {:text (str "v" version) :weight 0}]
     [:button {:text "Export…" :weight 0 :enabled (if (:base doc) 1 0)
               :on-click #(if @s/export-open* (act/close-export!) (act/open-export!))
               :description (str "Export the image (" (sc/label :export) ")")}]]))

(def ^:private zoom-steps [["Fit" nil] ["25%" 0.25] ["50%" 0.5] ["100%" 1.0] ["200%" 2.0] ["400%" 4.0]])

(defn- zoom-index [{:keys [scale zoom]}]
  (if (= zoom :fit)
    0
    (or (first (keep-indexed (fn [i [_ z]] (when (and z (< (Math/abs (- z scale)) 1e-6)) i)) zoom-steps))
        0)))

(defn- zoom-menu [view]
  [:dropdown {:items (mapv first zoom-steps) :weight 0 :value (zoom-index view)
              :on-change #(if-let [z (second (nth zoom-steps (long %)))]
                            (act/zoom-to! z)
                            (act/zoom-fit!))}])

(defn- job-progress [{:keys [label done total]}]
  [:hbox {:weight 0 :spacing (px 6)}
   [:label {:text label :weight 0}]
   [:progress {:weight 0 :reqwidth (px 180)
               :factor (if total (/ (double done) total) -1)}]])

(defn status-bar []
  (let [doc (s/current-doc)
        job @s/job*
        view @s/view*]
    [:hbox {:padding 4 :spacing 8}
     [:button {:text "Add images" :weight 0 :on-click act/open-dialog!
               :description (str "Add images (" (sc/label :open) ")")}]
     [:button {:text "Remove" :weight 0 :enabled (if doc 1 0)
               :on-click #(some-> @s/current* act/remove-doc!)}]
     [:label {:text @s/message* :weight 1}]
     (when job (job-progress job))
     (when-let [[w h] (when (:w doc) (g/display-size doc false))]
       [:label {:text (str "W " w "  H " h) :weight 0}])
     [:label {:text (v/zoom-label view) :weight 0}]
     (zoom-menu view)]))

;; One canvas, not a row of widgets: the kit's scroll view only scrolls
;; vertically, so the strip draws its slots itself and scrolls on the wheel
;; or a sideways swipe.
(def ^:private thumb-w 88)
(def ^:private thumb-h 59)
(defn- strip-geom [] {:pad (px 6) :slot (px thumb-w) :gap (px 8)})

(defonce ^:private strip (r/atom {:off 0.0 :w 0}))

(defn- reveal-current!
  "Scroll the strip so the selected thumbnail is in view."
  []
  (let [ids (mapv :id (d/docs @s/db*))]
    (when-let [i (first (keep-indexed #(when (= %2 @s/current*) %1) ids))]
      (swap! strip (fn [{:keys [off w] :as st}]
                     (cond-> st (pos? w) (assoc :off (strip/reveal off i (count ids) w (strip-geom)))))))))

(defonce ^:private reveal-watch
  (r/-add-watch! s/current* (fn [_] (reveal-current!))))

(defn- strip-wheel [delta _mods]
  (let [n (count (d/docs @s/db*))]
    (swap! strip (fn [{:keys [off w] :as st}]
                   (assoc st :off (strip/clamp-offset (- off (* (double delta) (px 40))) n w (strip-geom)))))))

(defn- strip-press [x _]
  (let [ids (mapv :id (d/docs @s/db*))
        {:keys [off]} @strip]
    (when-let [i (strip/index-at x off (count ids) (strip-geom))]
      (act/select-doc! (nth ids i)))))

(defn- thumb-items [i id selected? off g]
  (let [{ptr :thumb rev :thumb-rev :keys [name status]} (s/doc id)
        [iw ih] load/thumb-size
        tw (px thumb-w) th (px thumb-h)
        x (strip/slot-x i off g) y (:pad g)
        base (+ 10 (* 4 i))]
    (cond-> [(if ptr
               {:id base :type :image :coords [x y (+ x tw) (+ y th)] :rgba ptr :iw iw :ih ih :fit 1 :version rev}
               {:id base :type :text :coords [(+ x (/ tw 2.0)) (+ y (/ th 2.0))] :anchor 0
                :text (if (= :error status) "error" "…") :fill [1 1 1 0.5]})
             {:id (+ base 1) :type :text :coords [x (+ y th (px 10))] :anchor 4 :fill [1 1 1 0.85]
              :text (if (> (count name) 15) (str (subs name 0 13) "…") name)}]
      selected? (conj {:id (+ base 2) :type :rect :coords [(+ x 1) (+ y 1) (+ x tw -1) (+ y th -1)]
                       :outline [0.3 0.6 1 1] :width 3}))))

(defn filmstrip []
  (let [ids (mapv :id (d/docs @s/db*))
        cur @s/current*
        g (strip-geom)
        n (count ids)
        {:keys [w]} @strip
        off (strip/clamp-offset (:off @strip) n w g)
        h (+ (px thumb-h) (px 20) (* 2 (:pad g)))
        full (strip/content-width n g)]
    [:canvas {:reqheight h :events 1
              :on-configure (fn [cw _]
                              (swap! strip assoc :w (long cw))
                              (reveal-current!))
              :on-press strip-press
              :on-wheel strip-wheel
              :canvas-items
              (if (seq ids)
                (cond-> (into [] (mapcat (fn [i id] (thumb-items i id (= id cur) off g)) (range) ids))
                  (> full w)                    ; a scroll indicator along the bottom
                  (conj {:id 2 :type :rect :fill [1 1 1 0.35] :outline [0 0 0 0] :width 0
                         :coords (let [x0 (* w (/ off full)) x1 (* w (/ (+ off w) full))]
                                   [x0 (- h (px 4)) x1 (- h (px 1))])}))
                [{:id 1 :type :text :coords [(:pad g) (/ h 2.0)] :anchor 4 :fill [1 1 1 0.85]
                  :text "No images yet: use Add images."}])}]))
