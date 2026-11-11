(ns almaz.autopilot-test
  "Autopilot: image estimates -> which filters to add and how strong."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.autopilot :as ap]))

(deftest suggestions
  (testing "a clean, sharp image needs nothing"
    (is (= [] (mapv :kind (ap/suggest {:noise 0.002 :sharpness 0.9})))))
  (testing "visible noise adds denoise, stronger for more noise"
    (let [lo (first (ap/suggest {:noise 0.012 :sharpness 0.9}))
          hi (first (ap/suggest {:noise 0.04 :sharpness 0.9}))]
      (is (= :denoise (:kind lo) (:kind hi)))
      (is (< (:opacity lo) (:opacity hi)))
      (is (<= (:opacity hi) 0.6))))
  (testing "soft images add sharpen after denoise"
    (is (= [:denoise :sharpen] (mapv :kind (ap/suggest {:noise 0.03 :sharpness 0.1}))))
    (is (= [:sharpen] (mapv :kind (ap/suggest {:noise 0.001 :sharpness 0.1})))))
  (testing "strengths are moderate: in [0.15, 0.6], even for the worst image"
    (doseq [est [{:noise 0.5 :sharpness 0.0} {:noise 0.0061 :sharpness 0.299}]
            s (ap/suggest est)]
      (is (<= 0.15 (:opacity s) 0.6)))))

(deftest describe
  (is (string? (ap/describe {:noise 0.03 :sharpness 0.2}))))

(deftest noisy
  (is (ap/noisy? {:noise 0.02}))
  (is (not (ap/noisy? {:noise 0.001})))
  (is (not (ap/noisy? nil))))

(defn- hist-with
  "A 256-bin luma histogram: `n` pixels at each [bin n]."
  [& bins]
  (reduce (fn [h [i n]] (update h i + n)) (vec (repeat 256 0)) bins))

(deftest auto-exposure
  (testing "lifts the image until its brightest 1% reaches white"
    ;; 99% at bin 50, 1% at bin 64 (sRGB 0.251, 0.0513 linear): 2^4.28 brings it to 1.0
    (is (< 4.2 (ap/auto-exposure (hist-with [50 990] [64 10])) 4.35)))
  (testing "an image that already reaches white needs nothing"
    (is (= 0.0 (ap/auto-exposure (hist-with [100 900] [255 100])))))
  (testing "a dark frame is lifted at most to the slider's end"
    (is (= 5.0 (ap/auto-exposure (hist-with [1 1000])))))
  (testing "an empty histogram leaves exposure alone"
    (is (= 0.0 (ap/auto-exposure (vec (repeat 256 0)))))))
