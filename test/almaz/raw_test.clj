(ns almaz.raw-test
  "Decode the bundled Nikon Z9 HE NEF through the LibRaw shim. Needs
   native/raw/build.sh and DSC_9852.NEF in the project root."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.lens :as lens]
            [almaz.raw :as raw]
            [almaz.pixels :as px]))

(def nef "DSC_9852.NEF")

(deftest raw-file-detection
  (is (raw/raw-file? "/a/b/DSC_1.NEF"))
  (is (raw/raw-file? "x.cr3"))
  (is (not (raw/raw-file? "x.jpg")))
  (is (not (raw/raw-file? "noext"))))

(defn- have-nef? []
  (or (.exists (java.io.File. nef))
      (do (println "skipping: DSC_9852.NEF (Nikon Z 9 HE) isn't public") false)))

(deftest full-decode
  (when (have-nef?)
  (testing "full resolution, demosaiced, plausible colours"
    (let [img (raw/decode nef)]
      (is (= [8280 5520] [(px/width img) (px/height img)]))
      (let [[r g b] (px/pixel img 4140 2760)]
        (is (every? #(< 0.0 % 1.0) [r g b]) "no channel clipped at the centre"))
      (px/free-image! img)))
  (testing "no automatic brightening: the shot keeps its exposure"
    ;; LibRaw's auto-brightening scales every image until 1% of it clips; it
    ;; lifted this underexposed frame by ~2.8 EV (mean luma 127 instead of 48)
    (let [img (raw/decode nef)
          {:keys [l]} (px/histogram img [])
          mean (/ (reduce + (map-indexed * l)) (double (reduce + l)))]
      (is (< 40 mean 60) (str "mean luma " mean))
      (px/free-image! img)))))

(deftest embedded-thumbnail
  (when (have-nef?)
  (let [t (raw/thumbnail nef)]
    (is (some? t))
    (is (pos? (px/width t)))
    (px/free-image! t))))

(deftest decode-error
  (is (thrown? Exception (raw/decode "nope.NEF"))))

(def nef-meta
  {:make "NIKON CORPORATION" :model "NIKON Z 9" :lens "NIKKOR Z 180-600mm f/5.6-6.3 VR"
   :taken "2026:10:10 11:30:31" :iso 5000 :focal35 480 :orientation 1})

(deftest camera-metadata
  (when (have-nef?)
  (testing "the NEF's own EXIF (it is TIFF-structured)"
    (let [m (px/read-meta nef)]
      (is (= nef-meta (select-keys m (keys nef-meta))))
      (is (< (Math/abs (- 0.0005 (:exposure m))) 1e-9))
      (is (= 8.0 (:fnumber m)))
      (is (= 480.0 (:focal m)))))
  (testing "LibRaw's decoded fields as the fallback for non-TIFF RAW formats"
    (let [m (raw/metadata nef)]
      (is (= 5000 (:iso m)))
      (is (= 8.0 (:fnumber m)))
      (is (< (Math/abs (- 0.0005 (:exposure m))) 1e-9))
      (is (= "2026:10:10 11:30:31" (:taken m)))))))

;; Sony A7 III, uncompressed 14-bit (raw.pixls.us, CC0): native/tools/fetch_samples.sh
(def arw "ILCE-7M3.ARW")
(defn- have? [f] (.exists (java.io.File. f)))

(deftest sony-arw
  (if-not (have? arw)
    (println "skipping sony-arw: run native/tools/fetch_samples.sh")
    (do
      (testing "decodes at full resolution with plausible colours"
        (let [img (raw/decode arw)
              [r g b] (px/pixel img (quot (px/width img) 2) (quot (px/height img) 2))]
          (is (= [6024 4024] [(px/width img) (px/height img)]))
          (is (every? #(< 0.0 % 1.0) [r g b]))
          (px/free-image! img)))
      (testing "its EXIF reads (ARW is TIFF-structured), and LibRaw agrees"
        (let [m (px/read-meta arw)]
          (is (= {:make "SONY" :model "ILCE-7M3" :lens "FE 16-35mm F2.8 GM"
                  :taken "2018:03:14 10:42:42" :iso 400 :focal35 35}
                 (select-keys m [:make :model :lens :taken :iso :focal35])))
          (is (< (Math/abs (- 0.008 (:exposure m))) 1e-9))
          (is (= 5.0 (:fnumber m)))
          (is (= 400 (:iso (raw/metadata arw))))))
      (testing "its embedded lens corrections (DistortionCorrParams & co.)"
        (let [{:keys [distortion ca vignetting] :as c} (px/sony-corrections arw)]
          (is (= 16 (first distortion)))
          (is (= 32 (first ca)))
          (is (= 16 (first vignetting)))
          (is (lens/sony-valid? c))))
      (testing "the NEF has none"
        (is (nil? (px/sony-corrections nef))))
      (testing "the embedded preview"
        (let [t (raw/thumbnail arw)]
          (is (pos? (px/width t)))
          (px/free-image! t))))))
