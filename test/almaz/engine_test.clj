(ns almaz.engine-test
  "The tiled processing engine: a layer's filter over the composite below it."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.engine :as eng]
            [almaz.models :as models]
            [almaz.pixels :as px]
            [jolt.ffi :as ffi]))

(defn- filled [w h f]
  (let [img (px/new-image w h) data (float-array (* w h 3))]
    (dotimes [y h]
      (dotimes [x w]
        (let [i (* 3 (+ (* y w) x)) [r g b] (f x y)]
          (aset data i (float r)) (aset data (inc i) (float g)) (aset data (+ i 2) (float b)))))
    (px/set-pixels! img data)
    img))

(defn- gauss [^java.util.Random rng sigma]
  (let [u1 (max 1e-12 (.nextDouble rng)) u2 (.nextDouble rng)]
    (* sigma (Math/sqrt (* -2.0 (Math/log u1))) (Math/cos (* 2.0 Math/PI u2)))))

(defn- max-diff [a b w h]
  (apply max (for [y (range 0 h 5) x (range 0 w 5)]
               (apply max (map #(Math/abs (- %1 %2)) (px/pixel a x y) (px/pixel b x y))))))

(def identity-op
  {:block 128 :overlap 32
   :run (fn [in out n] (ffi/copy in out (* 4 n)))})

(deftest identity-filter
  (testing "an identity filter reproduces the composite below, seamlessly"
    (let [w 333 h 250
          base (filled w h (fn [x y] [(/ x (double w)) (/ y (double h)) 0.5]))
          over (filled w h (constantly [1 0 0]))
          below [{:img over :opacity 0.5 :enabled true}]
          expect (px/new-image w h)
          out (px/new-image w h)
          seen (atom [])]
      (px/composite-into! expect base below)
      (is (= :done (eng/process! {:base base :below below :out out :op identity-op
                                  :focus [0 0] :progress #(swap! seen conj [%1 %2])})))
      (is (< (max-diff expect out w h) 1e-4))
      (testing "progress counts every tile"
        (is (= (count @seen) (second (last @seen))))
        (is (= (map first @seen) (range 1 (inc (count @seen))))))
      (doseq [i [base over expect out]] (px/free-image! i)))))

(deftest cancellation
  (let [base (filled 400 400 (constantly [0.5 0.5 0.5]))
        out (px/new-image 400 400)
        n (atom 0)]
    (is (= :cancelled (eng/process! {:base base :below [] :out out :op identity-op :focus [0 0]
                                     :progress (fn [_ _] (swap! n inc))
                                     :cancelled? #(>= @n 2)})))
    (is (= 2 @n))
    (px/free-image! base)
    (px/free-image! out)))

(deftest classic-sharpen
  (testing "unsharp leaves flat areas alone and steepens edges"
    (let [base (filled 200 200 (fn [x _] (if (< x 100) [0.3 0.3 0.3] [0.7 0.7 0.7])))
          out (px/new-image 200 200)]
      (eng/process! {:base base :below [] :out out :focus [0 0]
                     :op (eng/op-for {:kind :sharpen :model :classic :amount 1.0 :radius 2.0} nil)})
      (is (< (Math/abs (- 0.3 (first (px/pixel out 20 100)))) 1e-3))
      (is (< (first (px/pixel out 98 100)) 0.3) "undershoot on the dark side of the edge")
      (is (> (first (px/pixel out 101 100)) 0.7) "overshoot on the bright side")
      (px/free-image! base)
      (px/free-image! out))))

(deftest ai-denoise
  (if-not (models/available? :denoise)
    (println "skipping ai-denoise: the denoise model is not downloaded (the app fetches it on first use)")
    (testing "NAFNet removes most of the noise of a noisy gradient"
      (let [rng (java.util.Random. 3) w 600 h 400
            base (filled w h (fn [x y] (let [v (+ 0.25 (* 0.5 (/ x (double w))))]
                                         [(+ v (gauss rng 0.04)) (+ v (gauss rng 0.04)) (+ v (gauss rng 0.04))])))
            out (px/new-image w h)
            before (:noise (px/estimate base))]
        (is (= :done (eng/process! {:base base :below [] :out out :focus [0 0]
                                    :op (eng/op-for {:kind :denoise :model :ai :detail 0.0}
                                                    (models/session :denoise))})))
        (let [after (:noise (px/estimate out))]
          (is (< after (/ before 4)) (str "noise " before " -> " after)))
        (testing "and keeps the image: the mean level is unchanged"
          (is (< (Math/abs (- (first (px/pixel base 300 200)) (first (px/pixel out 300 200)))) 0.06)))
        (px/free-image! base)
        (px/free-image! out)))))

(deftest skips-masked-out-tiles
  (testing "tiles where the layer's mask is empty are not run, and the result says so"
    (let [base (filled 600 300 (constantly [0.5 0.5 0.5]))
          out (px/new-image 600 300)
          mask (px/new-mask 600 300 4 0.0)
          runs (atom 0)
          op (update identity-op :run (fn [f] (fn [& a] (swap! runs inc) (apply f a))))]
      (px/stroke! mask [60 150] [60 150] {:radius 40 :hardness 0.9})
      (is (= :partial (eng/process! {:base base :below [] :out out :op op :focus [0 0] :mask mask})))
      (is (< 0 @runs 15) (str @runs " of " 15 " tiles ran"))
      (px/free-mask! mask)
      (px/free-image! base)
      (px/free-image! out))))

(deftest repair
  (let [w 300 h 200
        spot? (fn [x y] (< (+ (Math/pow (- x 150) 2) (Math/pow (- y 100) 2)) 225))
        base (filled w h (fn [x y] (if (spot? x y) [1 0 1] [(/ x (double w)) (/ y (double h)) 0.4])))
        out (px/new-image w h)
        mask (px/new-mask w h 4 0.0)]
    (testing "nothing painted: the output is the composite below"
      (is (= :done (eng/repair! {:base base :below [] :out out :mask mask})))
      (is (every? true? (map #(< (Math/abs (- %1 %2)) 1e-3) [1 0 1] (px/pixel out 150 100)))))
    (px/stroke! mask [150 100] [150 100] {:radius 20 :hardness 1.0})
    (testing "a painted spot is filled from around it"
      (is (= :done (eng/repair! {:base base :below [] :out out :mask mask})))
      (let [[r g b] (px/pixel out 150 100)]
        (is (< (Math/abs (- r 0.5)) 0.08) (str "red " r))
        (is (< (Math/abs (- g 0.5)) 0.08) (str "green " g))
        (is (< (Math/abs (- b 0.4)) 0.05) (str "blue " b))))
    (testing "away from it, untouched"
      (is (every? true? (map #(< (Math/abs (- %1 %2)) 1e-3) (px/pixel base 20 20) (px/pixel out 20 20)))))
    (testing "cancelled between areas"
      (is (= :cancelled (eng/repair! {:base base :below [] :out out :mask mask :cancelled? (constantly true)}))))
    (px/free-mask! mask)
    (doseq [i [base out]] (px/free-image! i))))

(deftest incremental-repair
  (let [w 400 h 200
        spot? (fn [cx x y] (< (+ (Math/pow (- x cx) 2) (Math/pow (- y 100) 2)) 144))
        base (filled w h (fn [x y] (if (or (spot? 80 x y) (spot? 200 x y) (spot? 320 x y)) [1 0 1] [(/ x (double w)) 0.4 0.4])))
        out (px/new-image w h)
        mask (px/new-mask w h 4 0.0)
        paint! (fn [cx] (px/stroke! mask [cx 100] [cx 100] {:radius 18 :hardness 1.0}))
        red #(first (px/pixel out % 100))]
    (paint! 80) (paint! 200)
    (eng/repair! {:base base :below [] :out out :mask mask})
    (px/mask-take-dirty! mask)
    (let [before (px/pixel out 80 100)]
      (testing "a new stroke: only the repairs it touches are redone"
        (paint! 320)
        (let [dirty (px/mask-take-dirty! mask)]
          (is (some? dirty))
          (is (< 280 (first dirty) 320) (str dirty))
          (is (= :done (eng/repair! {:base base :below [] :out out :mask mask :dirty dirty})))
          (is (< (Math/abs (- (red 320) 0.8)) 0.1) (str "third spot " (red 320)))
          (is (= before (px/pixel out 80 100)) "the first, untouched")))
      (testing "nothing changed: nothing marked"
        (is (nil? (px/mask-take-dirty! mask))))
      (testing "restoring a spot (erasing its repair) brings the original back"
        (px/stroke! mask [200 100] [200 100] {:radius 30 :hardness 1.0 :erase? true})
        (eng/repair! {:base base :below [] :out out :mask mask :dirty (px/mask-take-dirty! mask)})
        (is (< 0.95 (red 200)) (str "magenta again: " (red 200)))
        (is (= before (px/pixel out 80 100)))))
    (px/free-mask! mask)
    (doseq [i [base out]] (px/free-image! i))))

(deftest lens-correction
  (let [w 300 h 200
        base (filled w h (fn [x y] [(/ x 299.0) (/ y 199.0) 0.5]))
        out (px/new-image w h)
        k (vec (repeat 3 (vec (repeat 32 0.5))))
        plan {:center [149.5 99.5] :rmax 200.0 :k k :vrmax 200.0 :vig nil}]
    (testing "no plan: the composite below as it is"
      (is (= :done (eng/lens! {:base base :below [] :out out :plan nil})))
      (is (< (max-diff base out w h) 1e-4)))
    (testing "a plan warps every band, reporting progress"
      (let [seen (atom [])]
        (is (= :done (eng/lens! {:base base :below [] :out out :plan plan
                                 :progress (fn [d t] (swap! seen conj [d t]))})))
        (is (= [2 2] (last @seen)))
        (is (< (Math/abs (- (first (px/pixel out 299 0)) (/ (+ 149.5 (* 0.5 149.5)) 299.0))) 2e-3))
        (is (< (Math/abs (- (first (px/pixel out 0 199)) (/ (* 0.5 149.5) 299.0))) 2e-3))))
    (testing "cancelled between bands"
      (is (= :cancelled (eng/lens! {:base base :below [] :out out :plan plan :cancelled? (constantly true)}))))
    (testing "settings as plan options"
      (is (= {:method :lensfun :dist? true :tca? false :vig? true :autofill? true :lens "X"}
             (eng/lens-opts {:lens-method :lensfun :lens-dist true :lens-tca false :lens-vig true
                             :lens-fill true :lens-id "X"}))))
    (doseq [i [base out]] (px/free-image! i))))

(deftest focus-filter
  (testing "the whole image is blurred at once, edges and all"
    (let [w 300 h 200
          base (filled w h (fn [x y] [(if (< x 150) 0.2 0.8) (/ y 199.0) 0.5]))
          out (px/new-image w h)
          layer {:focus-clarity 0.0 :focus-radius 80.0 :focus-blur 8.0}]
      (is (= :done (eng/focus! {:base base :below [] :out out :layer layer})))
      (let [[r] (px/pixel out 150 100) [r0] (px/pixel out 100 100)]
        (is (< 0.4 r 0.6) "the edge is smeared")
        (is (< (Math/abs (- r0 0.2)) 0.01) "far from it, unchanged"))
      (doseq [i [base out]] (px/free-image! i)))))
