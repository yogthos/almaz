(ns almaz.lens-test
  "almaz.lens: lensfun's matching, interpolation and models, and Sony's
   embedded corrections. Expected values are worked from lensfun's formulas
   (and its database's entries) by hand."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.lens :as lens]))

(defn- close? [eps a b] (< (Math/abs (- (double a) (double b))) eps))

(def nikon {:make "NIKON CORPORATION" :model "NIKON Z 9" :lens "NIKKOR Z 180-600mm f/5.6-6.3 VR"
            :focal 480.0 :fnumber 8.0 :focal35 480})
(def sony {:make "SONY" :model "ILCE-7M3" :lens "FE 16-35mm F2.8 GM" :focal 35.0 :fnumber 5.0 :focal35 35})

(deftest strings
  (is (lens/str= "NIKON CORPORATION" " Nikon   Corporation "))
  (is (not (lens/str= "Nikon" "Nikon Z")))
  (testing "words split by class, lone f and punctuation dropped, .0 stripped, sorted"
    (is (= ["180" "5.6" "6.3" "600" "mm" "nikkor" "vr" "z"] (lens/split-words "NIKKOR Z 180-600mm f/5.6-6.3 VR")))
    (is (= ["2" "50" "mm" "summicron"] (lens/split-words "Summicron 50mm f/2.0"))))
  (testing "fuzzy score: 200 * common / (words in both)"
    (let [pw (lens/split-words "NIKKOR Z 180-600mm f/5.6-6.3 VR")]
      (is (= 100 (lens/fuzzy-score pw "Nikkor Z 180-600mm f/5.6-6.3 VR" true)))
      (is (= (quot (* 8 200) (+ 8 9)) (lens/fuzzy-score pw "Nikkor Z 180-600mm f/5.6-6.3 VR TC" true)))
      (is (zero? (lens/fuzzy-score pw "Nikkor Z 100-400mm f/4.5-5.6 VR S" true))))))

(deftest guessing
  (is (= [180.0 600.0] (:focal (lens/guess-parameters {:model ["NIKKOR Z 180-600mm f/5.6-6.3 VR"]}))))
  (is (= 5.6 (first (:aperture (lens/guess-parameters {:model ["NIKKOR Z 180-600mm f/5.6-6.3 VR"]})))))
  (testing "a prime: max focal falls back to min"
    (is (= [50.0 50.0] (:focal (lens/guess-parameters {:model ["Summicron 50mm f/2"]})))))
  (testing "from calibrations when the name has nothing"
    (is (= [16.0 35.0] (:focal (lens/guess-parameters
                                {:model ["FE 16-35mm F2.8 GM"]
                                 :calibs [{:distortion [["ptlens" 16 16 0 0 0 0 0] ["ptlens" 35 35 0 0 0 0 0]]}]})))))
  (is (= "Lens 50mm" (lens/sanitize-lens "Lens 50mm (65535)"))))

(deftest detection
  (let [{:keys [camera] l :lens} (lens/detect nikon)]
    (is (= "Nikon Z 9" (first (:model camera))))
    (is (= 1.0 (double (:crop camera))))
    (is (= "Nikkor Z 180-600mm f/5.6-6.3 VR" (first (:model l)))))
  (let [{:keys [camera] l :lens} (lens/detect sony)]
    (is (= "ILCE-7M3" (first (:model camera))))
    (is (re-find #"16-35mm f/2.8 GM" (first (:model l)))))
  (is (nil? (:lens (lens/detect {:make "NIKON CORPORATION" :model "NIKON Z 9" :lens "No Such Lens 1234mm"})))))

(deftest hermite
  (testing "reproduces a straight line"
    (is (close? 1e-12 1.5 (lens/interpolate 0.0 1.0 2.0 3.0 0.5)))
    (is (close? 1e-12 1.25 (lens/interpolate nil 1.0 2.0 nil 0.25))))
  (is (close? 1e-12 2.0 (lens/interpolate 0.0 1.0 2.0 3.0 1.0))))

(def ^:private z180 (delay (:lens (lens/detect nikon))))

(deftest distortion-interpolation
  (testing "an exact focal returns its entry"
    (let [d (lens/interpolate-distortion @z180 1.0 400.0)]
      (is (= :ptlens (:model d)))
      (is (= [0.0004307 0.0054284 -0.0056489] (subvec (:terms d) 0 3)))
      (is (close? 1e-9 (* 400 (- 1 0.0004307 0.0054284 -0.0056489)) (:real-focal d)))))
  (testing "between: Hermite through 300, 400 | 500, 600 of term * focal, / focal"
    (let [d (lens/interpolate-distortion @z180 1.0 480.0)
          f [300 400 500 600] a [0.0005027 0.0004307 0.0044362 0.0009136]
          want (/ (apply lens/interpolate (concat (map * a f) [0.8])) 480.0)]
      (is (close? 1e-12 want (first (:terms d))))))
  (testing "beyond the calibrated range: the nearest entry"
    (is (= 600.0 (double (:focal (lens/interpolate-distortion @z180 1.0 800.0))))))
  (testing "a sensor much smaller than the calibration's still uses it; a bigger one can't"
    (is (some? (lens/interpolate-distortion @z180 1.5 480.0)))
    (is (nil? (lens/interpolate-distortion @z180 0.7 480.0)))))

(deftest tca-and-vignetting
  (let [t (lens/interpolate-tca @z180 1.0 400.0)]
    (is (= :poly3 (:model t)))
    (is (= [0.9999419 1.0000444] (subvec (:terms t) 0 2))))
  (testing "vignetting at a calibrated point is that point's"
    (is (= [-0.2168 0.4305 -0.5383] (:terms (lens/interpolate-vignetting @z180 1.0 180.0 8.0 1000.0)))))
  (testing "between points, a weighted mean (inside the calibrated values)"
    (let [[k1] (:terms (lens/interpolate-vignetting @z180 1.0 190.0 8.0 1000.0))]
      (is (< -0.2168 k1 -0.1989)))))

(deftest rescaling
  (testing "ptlens a,b,c in units of the real focal length (lensfun's hugin scaling)"
    (let [lcd {:model :ptlens :terms [0.01 0.02 0.03 0 0] :crop 1.0 :aspect 1.5}
          h (/ 50.0 (/ (Math/hypot 36 24) 1.0 (Math/hypot 1.5 1) 2))
          d (- 1 0.01 0.02 0.03)
          [a b c] (:terms (lens/rescale-distortion lcd 50.0))]
      (is (close? 1e-12 (/ (* 0.01 (Math/pow h 3)) (Math/pow d 4)) a))
      (is (close? 1e-12 (/ (* 0.02 h h) (Math/pow d 3)) b))
      (is (close? 1e-12 (/ (* 0.03 h) (* d d)) c))))
  (testing "a calibration made on a crop sensor scales to the photo's units"
    (let [lcd {:model :poly3 :terms [0.01 0 0 0 0] :aspect 1.5}
          k #(first (:terms (lens/rescale-distortion (assoc lcd :crop %) 50.0)))]
      (is (close? 1e-12 (* 2.25 (k 1.0)) (k 1.5))))))

(deftest models
  (is (= [1.0 2.0] (lens/distort {:model :ptlens :terms [0 0 0]} [1.0 2.0])))
  (let [[x] (lens/distort {:model :poly3 :terms [0.1]} [0.5 0.0])]
    (is (close? 1e-12 (* 0.5 (+ 1 (* 0.1 0.25))) x)))
  (is (= [1.01 0.99] (lens/tca-factors {:model :linear :terms [1.01 0.99]} 0.3))))

(deftest auto-scaling
  (let [m (fn [terms] (lens/modifier {:lens {} :crop 1.0 :focal 24.0 :w 6000 :h 4000}
                                     {:distortion {:model :poly3 :terms terms :crop 1.0 :aspect 1.5 :real-focal 24.0}}))]
    (testing "no distortion: nothing to fill"
      (is (close? 1e-6 1.001 (lens/auto-scale (m [0.0 0 0 0 0])))))
    (testing "pincushion (corners pulled out of the photo) needs enlarging"
      (is (> (lens/auto-scale (m [0.02 0 0 0 0])) 1.01)))
    (testing "the scaled corner lands on the photo's corner"
      (let [mm (m [0.02 0 0 0 0])
            s (lens/auto-scale mm)
            ns (:norm-scale mm)
            corner (* 0.5 (Math/hypot (:width mm) (:height mm)) ns)
            [x y] (lens/distort (:distortion mm) (mapv #(* (/ corner s) %) [(Math/cos (Math/atan2 3999 5999)) (Math/sin (Math/atan2 3999 5999))]))]
        (is (<= (Math/hypot x y) (* 1.0001 corner)))))))

(deftest auto-scaling-long-lenses
  (testing "the frame is filled at 480 mm too: no corner reads outside the photo"
    (let [p (lens/plan {:w 8280 :h 5520 :exif nikon} {:dist? true :tca? true :autofill? true})
          [cx cy] (:center p)
          corner (Math/hypot cx cy)
          k (fn [ch r] (let [t (* (/ r (:rmax p)) (dec lens/table-size)) i (long t)]
                         (nth (nth (:k p) ch) i)))]
      (doseq [ch [0 1 2]]
        (is (<= (* (k ch corner) cx) (+ cx 0.5)) (str "channel " ch))))))

(deftest lensfun-plans
  (let [p (lens/plan {:w 8280 :h 5520 :exif nikon} {:dist? true :tca? true :vig? true :autofill? true})
        [kr kg kb] (:k p)]
    (is (= :lensfun (:method p)))
    (is (= [4139.5 2759.5] (:center p)))
    (is (= #{:distortion :tca :vignetting} (:corrections p)))
    (is (= lens/table-size (count kg) (count (:vig p))))
    (testing "the centre is only scaled"
      (is (close? 1e-6 (/ 1.0 (:scale p)) (first kg))))
    (testing "vignetting brightens towards the corners, not the centre"
      (is (close? 1e-9 1.0 (first (:vig p))))
      (is (> (nth (:vig p) 800) 1.05)))
    (testing "TCA: red and blue differ from green"
      (is (not= (last kr) (last kg)))))
  (testing "switched off parts aren't planned"
    (is (= #{:distortion} (:corrections (lens/plan {:w 100 :h 80 :exif nikon} {:dist? true})))))
  (testing "nothing to go on: no plan"
    (is (nil? (lens/plan {:w 100 :h 80 :exif {:make "Acme" :model "X"}} {:dist? true})))
    (is (nil? (lens/plan {:w 100 :h 80 :exif (dissoc nikon :focal)} {:dist? true})))))

(def ^:private flat-sony
  {:distortion (into [16] (repeat 16 0)) :ca (into [32] (repeat 32 0)) :vignetting (into [16] (repeat 16 0))})

(deftest embedded
  (is (lens/sony-valid? flat-sony))
  (is (not (lens/sony-valid? (assoc flat-sony :ca [16]))))
  (testing "zero coefficients correct nothing"
    (let [c (lens/sony-coeffs flat-sony {:dist? true :tca? true :vig? true})]
      (is (every? #(= 1.0 %) (apply concat (:cor c))))
      (is (close? 1e-12 1.0 (lens/md-auto-scale c)))
      ;; 2^(0.5 - 2^-1) = 1, squared
      (is (every? #(close? 1e-12 1.0 %) (:vig c)))))
  (testing "distortion coefficients scale the source radius by 1 + d / 2^14"
    (let [c (lens/sony-coeffs (assoc flat-sony :distortion (into [16] (repeat 16 1638))) {:dist? true})]
      (is (close? 1e-9 (+ 1 (/ 1638 16384.0)) (first (second (:cor c)))))))
  (testing "embedded data wins over lensfun when present (method :auto)"
    (let [p (lens/plan {:w 600 :h 400 :exif sony
                        :embedded (assoc flat-sony :distortion (into [16] (repeat 16 1638)))}
                       {:method :auto :dist? true :autofill? true})]
      (is (= :embedded (:method p)))
      (is (= [300.0 200.0] (:center p)))
      ;; autofill divides the uniform 1.1 out again
      (is (close? 1e-9 1.0 (first (second (:k p)))))))
  (testing "method :lensfun ignores it"
    (is (= :lensfun (:method (lens/plan {:w 600 :h 400 :exif sony :embedded flat-sony}
                                        {:method :lensfun :dist? true}))))))
