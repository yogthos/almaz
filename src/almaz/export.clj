(ns almaz.export
  "Export settings: what the export panel offers per format, what the writer
   (almaz.pixels/save-image!) is asked for, and how the panel describes the
   file it will make. Pure."
  (:require [clojure.string :as str]))

(def formats
  "[key label extension] in the order the panel lists them."
  [[:jpeg "JPEG" "jpg"] [:png "PNG" "png"] [:tiff "TIFF" "tif"]])

(def profiles
  "[key label]: the colour spaces an export can be written in. The engine
   works in sRGB; the others are converted into on write."
  [[:srgb "sRGB"] [:p3 "Display P3"] [:adobe "Adobe RGB (1998)"] [:none "None (untagged)"]])

(def png-depths [8 16])
(def tiff-compressions [[:none "None"] [:lzw "LZW"] [:deflate "Deflate"]])

(def defaults
  {:format :jpeg :quality 90 :profile :srgb
   :png-depth 16 :png-level 3 :tiff-compression :deflate})

(defn- clamp [lo hi v] (max lo (min hi (long v))))

(defn save-opts
  "The writer's {:format :quality :compression :profile} for `settings`."
  [{:keys [format quality profile png-depth png-level tiff-compression]}]
  (case format
    :jpeg {:format :jpeg :quality (clamp 1 100 quality) :compression 0 :profile profile}
    :png  {:format (if (= 8 png-depth) :png8 :png16) :quality 0
           :compression (clamp 0 9 png-level) :profile profile}
    :tiff {:format :tiff16 :quality 0
           :compression ({:none 0 :lzw 1 :deflate 2} tiff-compression 2) :profile profile}))

(defn ext [{:keys [format]}]
  (some (fn [[k _ e]] (when (= k format) e)) formats))

(defn label [{:keys [format]}]
  (some (fn [[k l _]] (when (= k format) l)) formats))

(defn file-name
  "The suggested name for exporting `source-name` with `settings`."
  [source-name settings]
  (str (str/replace source-name #"\.[^.]+$" "") "-almaz." (ext settings)))

(defn with-ext
  "`path` ending in the format's extension (any case kept), added if missing."
  [path settings]
  (let [e (str "." (ext settings))]
    (if (str/ends-with? (str/lower-case path) e) path (str path e))))

(defn human-size
  "A byte count as Finder shows it (decimal units)."
  [n]
  (let [n (double n)]
    (cond
      (< n 1e3) (str (long n) " bytes")
      (< n 1e6) (str (Math/round (/ n 1e3)) " KB")
      (< n 1e9) (let [mb (/ n 1e6)]
                  (if (< mb 10) (format "%.1f MB" mb) (str (Math/round mb) " MB")))
      :else (format "%.2f GB" (/ n 1e9)))))

(defn info
  "The panel's \"94 KB – 2992 × 1934 px\" line; `bytes` nil while estimating."
  [bytes [w h]]
  (str (if bytes (human-size bytes) "Estimating…") " – " w " × " h " px"))
