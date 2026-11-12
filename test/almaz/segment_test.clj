(ns almaz.segment-test
  "Automatic masks: the menu, and model outputs written into masks (a
   synthetic output; the models themselves are tested by running the app).
   Needs native/img/build.sh."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.models :as models]
            [almaz.pixels :as px]
            [almaz.segment :as seg]
            [jolt.ffi :as ffi]))

(deftest menu
  (is (= ["All" "Subject" "Background" "Portrait" "Landscape" "Sky" "Custom"] seg/labels))
  (is (nil? (seg/source :all)))
  (is (nil? (seg/source :custom)))
  (is (= {:model :subject :channel 0 :invert? true} (seg/source :background)))
  (is (= :scene (:model (seg/source :sky)))))

(defn- output
  "An n x n, `channels`-deep model output whose channel c is (f c x y)."
  [n channels f logits?]
  (let [p (ffi/alloc (* 4 channels n n))]
    (dotimes [c channels]
      (dotimes [y n]
        (dotimes [x n]
          (ffi/write p :float (float (f c x y)) (* 4 (+ (* c n n) (* y n) x))))))
    {:ptr p :n n :channels channels :logits? logits?}))

(deftest masks-from-outputs
  (let [m (px/new-mask 400 200 4 1.0)]
    (px/mask-feather! m 0.0)
    (testing "a probability map stretched over the image: the left half of channel 2"
      (let [o (output 16 3 (fn [c x _] (if (and (= c 2) (< x 8)) 1.0 0.0)) false)]
        (seg/apply! m :landscape o)
        (is (> (px/mask-at m 50 100) 0.95))
        (is (< (px/mask-at m 350 100) 0.05))
        (ffi/free (:ptr o))))
    (testing "logits go through a sigmoid, and Background inverts the subject"
      (let [o (output 16 1 (fn [_ _ y] (if (< y 8) 12.0 -12.0)) true)]
        (seg/apply! m :subject o)
        (is (> (px/mask-at m 200 20) 0.95))
        (is (< (px/mask-at m 200 180) 0.05))
        (seg/apply! m :background o)
        (is (< (px/mask-at m 200 20) 0.05))
        (is (> (px/mask-at m 200 180) 0.95))
        (ffi/free (:ptr o))))
    (px/free-mask! m)))

(deftest subject-model-devices
  ;; Core ML can't take BiRefNet's deformable convolutions (it fails after
  ;; ~20 s of compiling, and runs it no faster with the pads fixed), and
  ;; loads the scene model for longer than the CPU takes to run it
  (is (= [:cpu] (map :ep (models/devices :subject))))
  (is (= [:cpu] (map :ep (models/devices :scene))))
  (is (= :cpu (:ep (last (models/devices :denoise))))))
