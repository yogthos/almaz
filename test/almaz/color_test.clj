(ns almaz.color-test
  "The colour adjustments as pure functions on display (sRGB) colours, and
   the tables the renderer draws them with."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.color :as c]))

(defn- close? [eps a b] (< (Math/abs (- (double a) (double b))) eps))
(defn- rgb= [eps x y] (every? true? (map (partial close? eps) x y)))
(defn- finite? [x] (let [x (double x)] (and (== x x) (< (Math/abs x) 1e300))))
(defn- spread [[r g b]] (- (max r g b) (min r g b)))

(defn- apply-kind [kind params rgb]
  ((c/transform kind (merge (c/defaults kind) params)) rgb))

(def colours [[0.8 0.2 0.1] [0.1 0.6 0.3] [0.2 0.3 0.9] [0.5 0.5 0.5] [0.9 0.85 0.2] [0.0 0.0 0.0] [1.0 1.0 1.0]])

(deftest oklab
  (testing "round trip"
    (doseq [rgb colours]
      (is (rgb= 1e-6 rgb (c/oklab->srgb (c/srgb->oklab rgb))) (pr-str rgb))))
  (testing "greys have no chroma"
    (let [[_ a b] (c/srgb->oklab [0.4 0.4 0.4])]
      (is (close? 1e-6 0 a))
      (is (close? 1e-6 0 b)))))

(deftest neutral
  (testing "default settings change nothing, and need no table"
    (doseq [k [:hsl :selective :balance :levels]]
      (is (nil? (c/table k (c/defaults k))) (name k))
      (doseq [rgb colours]
        (is (rgb= 1e-4 rgb (apply-kind k {} rgb)) (str k " " (pr-str rgb))))))
  (testing "a vignette darkens by default, and is neutral at no exposure"
    (is (some? (c/table :vignette (c/defaults :vignette))))
    (is (nil? (c/table :vignette (assoc (c/defaults :vignette) :vig-exposure 0.0))))))

(deftest hue-saturation
  (testing "saturation -100% is grey"
    (is (< (spread (apply-kind :hsl {:hs-sat -1.0} [0.8 0.3 0.2])) 1e-3)))
  (testing "saturation up spreads the channels, inside the gamut"
    (let [out (apply-kind :hsl {:hs-sat 1.0} [0.7 0.4 0.3])]
      (is (> (spread out) 0.4))
      (is (every? #(<= 0.0 % 1.0) out))))
  (testing "a half turn of hue makes red cyan-ish"
    (let [[r g b] (apply-kind :hsl {:hs-hue 1.0} [0.8 0.2 0.2])]
      (is (and (> g r) (> b r)))))
  (testing "vibrance lifts muted colours more than vivid ones"
    (let [gain (fn [rgb] (/ (spread (apply-kind :hsl {:hs-vib 1.0} rgb)) (spread rgb)))]
      (is (> (gain [0.55 0.5 0.45]) (gain [0.9 0.2 0.1]))))))

(deftest selective
  (testing "a band's settings move its colours, not the others"
    (let [p {:sel-red-sat -1.0}]
      (is (< (spread (apply-kind :selective p [0.8 0.15 0.1])) 0.05))
      (is (rgb= 0.01 [0.15 0.3 0.9] (apply-kind :selective p [0.15 0.3 0.9])))))
  (testing "greys stay grey whatever the bands do"
    (let [p {:sel-red-bri 1.0 :sel-green-hue 1.0 :sel-blue-sat 1.0}]
      (is (rgb= 1e-3 [0.5 0.5 0.5] (apply-kind :selective p [0.5 0.5 0.5])))))
  (testing "brightness lightens the band"
    (is (> (apply max (apply-kind :selective {:sel-blue-bri 1.0} [0.1 0.2 0.8])) 0.8))))

(deftest balance
  (testing "shadows towards red tint the darks, barely the lights"
    (let [[r _ b] (apply-kind :balance {:bal-shadows-cr 1.0} [0.15 0.15 0.15])
          [r2 _ b2] (apply-kind :balance {:bal-shadows-cr 1.0} [0.9 0.9 0.9])]
      (is (> (- r b) 0.03))
      (is (< (- r2 b2) (/ (- r b) 4)))))
  (testing "highlights towards blue tint the lights"
    (let [[r _ b] (apply-kind :balance {:bal-highlights-yb 1.0} [0.85 0.85 0.85])]
      (is (> b r))))
  (testing "a colour shift keeps the luminance"
    (let [lum (fn [rgb] (first (c/srgb->oklab rgb)))]
      (is (close? 0.01 (lum [0.5 0.5 0.5]) (lum (apply-kind :balance {:bal-midtones-mg 1.0} [0.5 0.5 0.5]))))))
  (testing "brightness by range"
    (is (> (first (apply-kind :balance {:bal-shadows-bri 1.0} [0.1 0.1 0.1])) 0.12))))

(deftest levels
  (let [lv (fn [p v] (first (apply-kind :levels p [v v v])))]
    (testing "black and white points stretch the range"
      (is (close? 1e-3 0.0 (lv {:lv-rgb-black 0.2 :lv-rgb-grey 0.5} 0.2)))
      (is (close? 1e-3 1.0 (lv {:lv-rgb-white 0.8 :lv-rgb-grey 0.5} 0.8)))
      (is (close? 1e-3 0.5 (lv {:lv-rgb-black 0.2 :lv-rgb-white 0.8 :lv-rgb-grey 0.5} 0.5))))
    (testing "the grey point maps to the middle"
      (is (close? 2e-3 0.5 (lv {:lv-rgb-grey 0.3} 0.3)))
      (is (> (lv {:lv-rgb-grey 0.3} 0.5) 0.5)))
    (testing "a channel's levels apply after the master's"
      (let [[r g] (apply-kind :levels {:lv-r-white 0.5 :lv-r-grey 0.25} [0.5 0.5 0.5])]
        (is (close? 1e-3 1.0 r))
        (is (close? 1e-3 0.5 g)))))
  (testing "the grey point keeps its place between black and white"
    (is (close? 1e-9 0.6 (:lv-rgb-grey (c/set-level (c/defaults :levels) :rgb :black 0.2))))))

(deftest pickers
  (let [p (c/defaults :levels)]
    (testing "a black pick per channel neutralises a cast in the shadows"
      (let [p (c/pick-level p :black [0.1 0.05 0.02])]
        (is (= [0.1 0.05 0.02] [(:lv-r-black p) (:lv-g-black p) (:lv-b-black p)]))))
    (testing "a grey pick makes that colour grey"
      (let [p (c/pick-level p :grey [0.5 0.4 0.3])
            [r g b] (apply-kind :levels p [0.5 0.4 0.3])]
        (is (close? 2e-3 r g))
        (is (close? 2e-3 g b))))))

(deftest vignette
  (testing "the curve darkens (or lightens) where the radial weight is"
    (is (< (first (apply-kind :vignette {:vig-exposure -0.5} [0.5 0.5 0.5])) 0.3))
    (is (> (first (apply-kind :vignette {:vig-exposure 0.5} [0.5 0.5 0.5])) 0.7)))
  (testing "centred on the crop, its radii the crop's halves"
    (let [[cx cy rx ry inner falloff] (c/radial (c/defaults :vignette) [100 50 400 200])]
      (is (= [300.0 150.0 200.0 100.0] [cx cy rx ry]))
      (is (< inner 1.0))
      (is (> (+ inner falloff) (Math/sqrt 2.0)) "full strength only beyond the corners")))
  (testing "softness 0 starts at the edges"
    (is (close? 1e-9 1.0 (nth (c/radial (assoc (c/defaults :vignette) :vig-softness 0.0) [0 0 10 10]) 4)))))

(deftest black-and-white
  (testing "every colour comes out grey"
    (doseq [rgb colours]
      (is (< (spread (apply-kind :bw {} rgb)) 1e-3) (pr-str rgb))))
  (testing "greys keep their tone"
    (is (rgb= 2e-3 [0.5 0.5 0.5] (apply-kind :bw {} [0.5 0.5 0.5]))))
  (testing "the red filter lightens reds"
    (is (> (first (apply-kind :bw {:bw-red 1.0} [0.8 0.1 0.1]))
           (first (apply-kind :bw {} [0.8 0.1 0.1])))))
  (testing "tone warms the mids"
    (let [[r _ b] (apply-kind :bw {:bw-tone 1.0} [0.5 0.5 0.5])]
      (is (> r b))))
  (testing "no intensity is the original"
    (is (rgb= 1e-3 [0.8 0.2 0.1] (apply-kind :bw {:bw-intensity 0.0} [0.8 0.2 0.1])))))

(deftest tables
  (testing "a cube reproduces its transform at the grid points"
    (let [p (merge (c/defaults :hsl) {:hs-sat 0.5})
          {:keys [n data]} (:cube (c/table :hsl p))
          f (c/transform :hsl p)
          i (* 3 (+ 4 (* n (+ 10 (* n 20)))))
          s (/ 1.0 (dec n))]
      (is (rgb= 1e-5 (f [(* 4 s) (* 10 s) (* 20 s)]) [(aget data i) (aget data (inc i)) (aget data (+ i 2))]))))
  (testing "levels and vignettes are curves (three channels of 4096)"
    (is (= (* 3 4096) (alength (:curves (c/table :levels (assoc (c/defaults :levels) :lv-rgb-white 0.9)))))))
  (testing "the same settings give the same table (cached)"
    (let [p (assoc (c/defaults :bw) :bw-red 0.3)]
      (is (identical? (c/table :bw p) (c/table :bw p))))))

;; ---- ported from darktable's unit tests ------------------------------------------
;; test_colorin_rgb.c checks colour conversions against reference pixels with a
;; tolerance; here Oklab against Björn Ottosson's published values.
(deftest oklab-reference
  (testing "sRGB primaries and white (Ottosson's reference table)"
    (doseq [[rgb lab] [[[1.0 1.0 1.0] [1.0 0.0 0.0]]
                       [[1.0 0.0 0.0] [0.62796 0.22486 0.12585]]
                       [[0.0 1.0 0.0] [0.86644 -0.23389 0.17950]]
                       [[0.0 0.0 1.0] [0.45201 -0.03246 -0.31153]]]]
      (is (rgb= 2e-4 lab (c/srgb->oklab rgb)) (pr-str rgb)))))

;; test_draw_curve.c: curves are filled from stored params, "so counts,
;; indices and types can be anything a sidecar supplies" -- the result must
;; stay in range whatever they are.
(defn- extremes
  "Each setting of `kind` at its min and its max, one at a time, and all at
   once at each end."
  [kind]
  (let [s (c/settings kind)]
    (concat (for [[k [_ lo hi]] s v [lo hi]] {k v})
            [(into {} (map (fn [[k [_ lo]]] [k lo])) s)
             (into {} (map (fn [[k [_ _ hi]]] [k hi])) s)])))

(def ^:private probe
  (for [r [0.0 0.03 0.5 0.97 1.0] g [0.0 0.5 1.0] b [0.0 0.2 1.0]] [r g b]))

(deftest bounded-at-extremes
  (doseq [kind (keys c/settings) p (extremes kind) rgb probe]
    (let [out (apply-kind kind p rgb)]
      (is (and (= 3 (count out)) (every? #(and (finite? %) (<= 0.0 % 1.0)) out))
          (str kind " " p " " (pr-str rgb) " -> " (pr-str out))))))

(deftest degenerate-levels
  (testing "black at or past white, grey outside them: still a curve in 0..1"
    (doseq [[b g w] [[0.5 0.5 0.5] [0.8 0.5 0.2] [0.2 0.0 0.8] [0.2 1.0 0.8] [0.0 0.0 0.0] [1.0 1.0 1.0]]]
      (let [ys (map #(c/level b g w (/ % 32.0)) (range 33))]
        (is (every? #(and (finite? %) (<= 0.0 % 1.0)) ys) (pr-str [b g w]))
        (is (apply <= ys) (str "increasing " (pr-str [b g w]))))))
  (testing "set-level never lets black pass white"
    (let [p (-> (c/defaults :levels) (c/set-level :rgb :black 0.99) (c/set-level :rgb :white 0.0))]
      (is (< (:lv-rgb-black p) (:lv-rgb-white p)))
      (is (<= (:lv-rgb-black p) (:lv-rgb-grey p) (:lv-rgb-white p))))))

;; ---- curves ---------------------------------------------------------------------
(deftest curve-points
  (testing "a channel's points as text: none is the straight line"
    (is (= [[0.0 0.0] [1.0 1.0]] (c/parse-points "")))
    (is (= [[0.0 0.0] [1.0 1.0]] (c/parse-points nil)))
    (is (= [[0.0 0.1] [0.5 0.7] [1.0 0.9]] (c/parse-points "1,0.9;0,0.1;0.5,0.7")) "sorted by x")
    (is (= [[0.0 0.0] [1.0 1.0]] (c/parse-points "junk;0.5")) "unreadable: the line")
    (is (= [[0.0 0.0] [0.25 1.0] [1.0 1.0]] (c/parse-points "0,0;0.25,3;1,1")) "clamped to 0..1")
    (is (= "" (c/format-points [[0.0 0.0] [1.0 1.0]])))
    (is (= [[0.0 0.0] [0.3 0.45] [1.0 1.0]] (c/parse-points (c/format-points [[0.0 0.0] [0.3 0.45] [1.0 1.0]]))))))

(deftest curve-shape
  (let [pts [[0.0 0.0] [0.25 0.15] [0.75 0.85] [1.0 1.0]]
        f (c/curve-fn pts)]
    (testing "through every point"
      (doseq [[x y] pts] (is (close? 1e-9 y (f x)))))
    (testing "monotone points: a monotone curve, no overshoot"
      (let [ys (map #(f (/ % 200.0)) (range 201))]
        (is (apply <= ys))
        (is (every? #(<= 0.0 % 1.0) ys))))
    (testing "flat past the end points"
      (let [g (c/curve-fn [[0.2 0.1] [0.8 0.9]])]
        (is (close? 1e-9 0.1 (g 0.0)))
        (is (close? 1e-9 0.9 (g 1.0)))))
    (testing "a peak doesn't ring past its neighbours"
      (let [g (c/curve-fn [[0.0 0.0] [0.5 1.0] [1.0 0.0]])]
        (is (every? #(<= 0.0 (g (/ % 100.0)) 1.0) (range 101)))))))

(deftest curve-editing
  (let [line [[0.0 0.0] [1.0 1.0]]]
    (testing "a point goes in by x"
      (is (= [[[0.0 0.0] [0.4 0.6] [1.0 1.0]] 1] (c/add-point line 0.4 0.6))))
    (testing "a moved point stays between its neighbours"
      (let [pts [[0.0 0.0] [0.4 0.6] [0.7 0.8] [1.0 1.0]]
            [_ [x y] _] (c/move-point pts 1 0.9 1.5)]
        (is (< x 0.7))
        (is (close? 1e-9 1.0 y))))
    (testing "removing a point; an end point goes back to its corner"
      (is (= line (c/remove-point [[0.0 0.0] [0.4 0.6] [1.0 1.0]] 1)))
      (is (= [[0.0 0.0] [0.5 0.5] [1.0 1.0]] (c/remove-point [[0.1 0.2] [0.5 0.5] [1.0 1.0]] 0))))
    (testing "the point under a click, if any"
      (is (= 1 (c/point-near [[0.0 0.0] [0.4 0.6] [1.0 1.0]] 0.41 0.59 0.03)))
      (is (nil? (c/point-near [[0.0 0.0] [0.4 0.6] [1.0 1.0]] 0.2 0.8 0.03))))))

(deftest curves
  (let [lift "0,0;0.5,0.7;1,1"]
    (testing "the default curves change nothing"
      (is (nil? (c/table :curves (c/defaults :curves)))))
    (testing "RGB lifts every channel, as separable curves"
      (is (rgb= 1e-6 [0.7 0.7 0.7] (apply-kind :curves {:cv-rgb lift} [0.5 0.5 0.5])))
      (is (some? (:curves (c/table :curves (assoc (c/defaults :curves) :cv-rgb lift))))))
    (testing "Red only red"
      (is (rgb= 1e-6 [0.7 0.5 0.5] (apply-kind :curves {:cv-r lift} [0.5 0.5 0.5]))))
    (testing "Luminance brightens and keeps the hue, through a colour cube"
      (let [in [0.6 0.3 0.2]
            out (apply-kind :curves {:cv-l lift} in)
            [L0 a0 b0] (c/srgb->oklab in) [L1 a1 b1] (c/srgb->oklab out)]
        (is (> L1 L0))
        (is (close? 0.02 (Math/atan2 b0 a0) (Math/atan2 b1 a1)))
        (is (rgb= 1e-6 [0.7 0.7 0.7] (apply-kind :curves {:cv-l lift} [0.5 0.5 0.5])))
        (is (some? (:cube (c/table :curves (assoc (c/defaults :curves) :cv-l lift)))))))
    (testing "wild points still give colours in range"
      (doseq [s ["0,1;1,0" "0,0;0.01,1;0.02,0;1,1" "0.5,0.5" "0,1;1,1"]
              ch [:cv-l :cv-rgb :cv-g]
              rgb probe]
        (is (every? #(and (finite? %) (<= 0.0 % 1.0)) (apply-kind :curves {ch s} rgb)) (str ch s rgb))))))
