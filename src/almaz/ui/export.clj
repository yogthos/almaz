(ns almaz.ui.export
  "The export panel: shown in the sidebar's place while choosing how to
   export. The format's own settings (JPEG quality, PNG bit depth and
   compression, TIFF compression), the colour profile, and the size and
   dimensions the file will have."
  (:require [almaz.actions :as act]
            [almaz.export :as ex]
            [almaz.geom :as g]
            [almaz.state :as s]
            [almaz.ui.shortcuts :as sc]
            [almaz.ui.units :refer [px]]))

(defn- index-of [xs x] (or (first (keep-indexed #(when (= x %2) %1) xs)) 0))

(defn- row [label control]
  [:hbox {:spacing (px 6)}
   [:label {:text label :weight 0 :reqwidth (px 92)}]
   control])

(defn- format-options [{:keys [format quality png-depth png-level tiff-compression]}]
  (case format
    :jpeg [[:slider {:label "Quality" :min 1 :max 100 :integer 1 :value (long quality)
                     :on-change #(act/set-export! :quality (long %))}]]
    :png  [(row "Bit depth:"
                [:enum {:items (mapv #(str % "-bit") ex/png-depths) :weight 1
                        :value (index-of ex/png-depths png-depth)
                        :on-change #(act/set-export! :png-depth (nth ex/png-depths (long %)))}])
           [:slider {:label "Compression" :min 0 :max 9 :integer 1 :value (long png-level)
                     :description "zlib level: higher is smaller and slower"
                     :on-change #(act/set-export! :png-level (long %))}]]
    :tiff [(row "Compression:"
                [:enum {:items (mapv second ex/tiff-compressions) :weight 1
                        :value (index-of (mapv first ex/tiff-compressions) tiff-compression)
                        :on-change #(act/set-export! :tiff-compression (first (nth ex/tiff-compressions (long %))))}])
           [:label {:text "16 bits per channel"}]]))

(defn- info-text [doc]
  (let [size (g/display-size doc false)
        est @s/estimate*]
    (if (= (:key est) (s/estimate-key doc))
      (ex/info (:bytes est) size)
      (ex/info nil size))))

(defn panel [width]
  (let [doc (s/current-doc)
        settings @s/export-settings*]
    [:vbox {:weight 0 :reqwidth (px width) :padding (px 8) :spacing (px 8)}
     [:label {:text "Export" :bold 1 :font-scale 1.3}]
     (row "Format:"
          [:dropdown {:items (mapv second ex/formats) :weight 1
                      :value (index-of (mapv first ex/formats) (:format settings))
                      :on-change #(act/set-export! :format (first (nth ex/formats (long %))))}])
     (row "Color Profile:"
          [:dropdown {:items (mapv second ex/profiles) :weight 1
                      :value (index-of (mapv first ex/profiles) (:profile settings))
                      :on-change #(act/set-export! :profile (first (nth ex/profiles (long %))))}])
     (into [:vbox {:spacing (px 8)}] (format-options settings))
     (row "File Size:" [:label {:text (if (:w doc) (info-text doc) "") :weight 1}])
     [:label {:text "" :stretch true}]
     [:hbox {:spacing (px 6)}
      [:label {:text "" :weight 1}]
      [:button {:text "Cancel" :weight 0 :on-click act/close-export!
                :description (str "Close without exporting (" (sc/label :escape) ")")}]
      [:button {:text "Export…" :weight 0 :enabled (if (:base doc) 1 0) :on-click act/export!
                :description (str "Choose where to save (" (sc/label :export) ")")}]]]))
