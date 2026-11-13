(ns almaz.files
  "Which files the app takes: by extension, and what a dropped or opened
   folder holds."
  (:require [clojure.string :as str]
            [almaz.raw :as raw]))

(def image-extensions #{"jpg" "jpeg" "png" "tif" "tiff"})

(defn supported? [path]
  (or (raw/raw-file? path)
      (contains? image-extensions (str/lower-case (or (second (re-find #"\.([^./]+)$" path)) "")))))

(defn expand
  "The supported image files among `paths`, in order and without repeats; a
   folder stands for the images directly in it (by name, not hidden ones)."
  [paths]
  (->> paths
       (mapcat (fn [p]
                 (let [f (java.io.File. ^String p)]
                   (cond
                     (.isDirectory f)
                     (->> (.listFiles f)
                          (filter #(.isFile ^java.io.File %))
                          (map #(.getPath ^java.io.File %))
                          (remove #(str/starts-with? (last (str/split % #"/")) "."))
                          sort)
                     (.isFile f) [p]
                     :else []))))
       (filter supported?)
       distinct
       vec))
