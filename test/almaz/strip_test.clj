(ns almaz.strip-test
  "Filmstrip geometry: slots along a row, scrolled sideways."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.strip :as st]))

(def g {:pad 6 :slot 88 :gap 8})

(deftest content-width
  (is (= 12 (st/content-width 0 g)))
  (is (= (+ 12 88) (st/content-width 1 g)))
  (is (= (+ 12 (* 3 88) (* 2 8)) (st/content-width 3 g))))

(deftest clamping
  (testing "nothing to scroll when everything fits"
    (is (= 0.0 (st/clamp-offset 50 3 1000 g))))
  (testing "never past either end"
    (let [n 20 w 500 most (- (st/content-width n g) w)]
      (is (= 0.0 (st/clamp-offset -10 n w g)))
      (is (= (double most) (st/clamp-offset 1e6 n w g)))
      (is (= 100.0 (st/clamp-offset 100 n w g))))))

(deftest hit-testing
  (testing "a slot's x, shifted by the offset"
    (is (= 6.0 (st/slot-x 0 0 g)))
    (is (= (- (+ 6.0 (* 2 96)) 50.0) (st/slot-x 2 50 g))))
  (testing "a click maps to the slot under it, or nil between slots and past the end"
    (is (= 0 (st/index-at 10 0 5 g)))
    (is (= 1 (st/index-at (+ 6 96 1) 0 5 g)))
    (is (nil? (st/index-at (+ 6 88 2) 0 5 g)))     ; the gap
    (is (nil? (st/index-at 2 0 5 g)))              ; the padding
    (is (nil? (st/index-at 10000 0 5 g)))
    (is (= 1 (st/index-at 10 96 5 g)))))           ; scrolled one slot along

(deftest revealing
  (let [n 20 w 300]
    (testing "a visible slot leaves the offset alone"
      (is (= 0.0 (st/reveal 0 1 n w g))))
    (testing "a slot past the right edge scrolls just far enough (keeping the padding)"
      (is (= (double w) (+ (st/slot-x 10 (st/reveal 0 10 n w g) g) 88 6))))
    (testing "a slot left of the view scrolls back to it"
      (is (= 6.0 (st/slot-x 3 (st/reveal 1000 3 n w g) g))))))
