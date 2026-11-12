(ns almaz.pixels-test
  "The native pixel engine (native/img): images, masks, compositing, tiles,
   estimators and file I/O. Needs native/img/build.sh."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.infer.tile :as tile]
            [almaz.pixels :as px]
            [jolt.ffi :as ffi]))

(defn- close? [eps a b] (< (Math/abs (- (double a) (double b))) eps))

(defn- filled [w h f]
  (let [img (px/new-image w h)
        data (float-array (* w h 3))]
    (dotimes [y h]
      (dotimes [x w]
        (let [[r g b] (f x y)
              i (* 3 (+ (* y w) x))]
          (aset data i (float r))
          (aset data (inc i) (float g))
          (aset data (+ i 2) (float b)))))
    (px/set-pixels! img data)
    img))

(defn- gauss
  "A normal sample (Box-Muller; jolt's Random has no nextGaussian)."
  [^java.util.Random rng sigma]
  (let [u1 (max 1e-12 (.nextDouble rng)) u2 (.nextDouble rng)]
    (* sigma (Math/sqrt (* -2.0 (Math/log u1))) (Math/cos (* 2.0 Math/PI u2)))))

(deftest image-roundtrip
  (let [img (filled 4 3 (fn [x y] [(/ x 3.0) (/ y 2.0) 0.5]))]
    (is (= [4 3] [(px/width img) (px/height img)]))
    (is (every? true? (map (partial close? 1e-4) [1.0 0.5 0.5] (px/pixel img 3 1))))
    (px/free-image! img)))

(defn- render-1px
  "Render the stack scaled so the whole w x h image lands in one pixel."
  [base layers]
  (let [out (ffi/alloc 4)]
    (px/render! base layers {:ox 0 :oy 0 :scale (/ 1.0 (px/width base))
                             :out out :out-w 1 :out-h 1})
    (let [v (mapv #(bit-and 0xff %) (ffi/read-array out 4))]
      (ffi/free out)
      v)))

(deftest compositing
  (let [black (filled 8 8 (constantly [0 0 0]))
        white (filled 8 8 (constantly [1 1 1]))]
    (testing "opacity blends the layer over the base"
      (is (= [128 128 128 255] (render-1px black [{:img white :opacity 0.5 :enabled true}])))
      (is (= [0 0 0 255] (render-1px black [{:img white :opacity 1.0 :enabled false}]))))
    (testing "a mask limits where the layer applies"
      (let [m (px/new-mask 8 8 1 0.0)]
        (is (= [0 0 0 255] (render-1px black [{:img white :mask m :opacity 1.0 :enabled true}])))
        (px/mask-fill! m 1.0)
        (is (= [255 255 255 255] (render-1px black [{:img white :mask m :opacity 1.0 :enabled true}])))
        (px/free-mask! m)))
    (doseq [i [black white]] (px/free-image! i))))

(def neutral {:exposure 0.0 :contrast 0.0 :highlights 0.0 :shadows 0.0 :whites 0.0 :blacks 0.0 :saturation 0.0})

(defn- tone [params rgb] (px/apply-tone (merge neutral params) rgb))
(defn- grey [params v] (first (tone params [v v v])))

(deftest tone-curve
  (testing "neutral settings change nothing"
    (doseq [c [[0.0 0.0 0.0] [0.2 0.5 0.9] [1.0 1.0 1.0]]]
      (is (every? true? (map (partial close? 1e-3) c (tone {} c))) (pr-str c))))
  (testing "exposure scales linear light by 2^EV"
    ;; sRGB 0.5 is 0.214 linear; doubled, 0.428 linear is sRGB 0.686
    (is (close? 2e-3 0.686 (grey {:exposure 1.0} 0.5)))
    (is (close? 2e-3 0.5 (grey {:exposure -1.0} 0.686))))
  (testing "contrast steepens around the middle"
    (is (< (grey {:contrast 0.5} 0.25) 0.25))
    (is (> (grey {:contrast 0.5} 0.75) 0.75))
    (is (close? 1e-3 0.5 (grey {:contrast 0.5} 0.5)))
    (is (> (grey {:contrast -0.5} 0.25) 0.25)))
  (testing "shadows work on the dark end, highlights on the bright end"
    (let [up (- (grey {:shadows 1.0} 0.25) 0.25)]
      (is (pos? up))
      (is (< (- (grey {:shadows 1.0} 0.8) 0.8) (/ up 2))))
    (let [down (- 0.75 (grey {:highlights -1.0} 0.75))]
      (is (pos? down))
      (is (< (- 0.2 (grey {:highlights -1.0} 0.2)) (/ down 2)))))
  (testing "whites move the white point, blacks the black point"
    (is (> (grey {:whites 0.5} 0.9) 0.9))
    (is (< (grey {:blacks -0.5} 0.05) 0.05))
    (is (> (grey {:blacks 0.5} 0.0) 0.0)))
  (testing "every setting keeps the curve increasing and inside 0..1"
    (doseq [k (keys (dissoc neutral :saturation)) v [-1.0 1.0]]
      (let [ys (map #(grey {k v} (/ % 64.0)) (range 65))]
        (is (apply <= ys) (str k " " v))
        (is (every? #(<= 0.0 % 1.0) ys) (str k " " v)))))
  (testing "saturation scales colour away from (or towards) grey"
    (let [[r g b] (tone {:saturation -1.0} [0.8 0.4 0.2])]
      (is (close? 1e-3 r g))
      (is (close? 1e-3 g b)))
    (let [[r _ b] (tone {:saturation 0.5} [0.6 0.4 0.3])]
      (is (> (- r b) 0.3)))
    (is (every? true? (map (partial close? 1e-3) [0.5 0.5 0.5] (tone {:saturation 1.0} [0.5 0.5 0.5]))))))

(deftest light-layers
  (let [mid (filled 8 8 (constantly [0.5 0.5 0.5]))
        brighter (merge neutral {:exposure 1.0})
        v (fn [layers] (first (render-1px mid layers)))]
    (testing "an adjustment layer needs no pixels of its own"
      (is (= 175 (v [{:tone brighter :opacity 1.0 :enabled true}]))))
    (testing "it blends through its opacity and mask like any layer"
      (is (= 128 (v [{:tone brighter :opacity 1.0 :enabled false}])))
      (is (< 128 (v [{:tone brighter :opacity 0.5 :enabled true}]) 175))
      (let [m (px/new-mask 8 8 1 0.0)]
        (is (= 128 (v [{:tone brighter :mask m :opacity 1.0 :enabled true}])))
        (px/free-mask! m)))
    (px/free-image! mid)))

(defn- swap-cube
  "An n^3 cube that swaps red and blue."
  [n]
  (let [a (float-array (* 3 n n n)) s (/ 1.0 (dec n))]
    (dotimes [b n] (dotimes [g n] (dotimes [r n]
      (let [i (* 3 (+ r (* n (+ g (* n b)))))]
        (aset a i (float (* b s))) (aset a (+ i 1) (float (* g s))) (aset a (+ i 2) (float (* r s)))))))
    a))

(defn- render-at
  "The rendered RGB of the pixel at image (x, y), 1:1."
  [base layers x y]
  (let [out (ffi/alloc 4)]
    (px/render! base layers {:xf [1.0 0.0 (double x) 0.0 1.0 (double y)] :out out :out-w 1 :out-h 1})
    (let [v (mapv #(bit-and 0xff %) (ffi/read-array out 3))]
      (ffi/free out)
      v)))

(deftest colour-layers
  (let [img (filled 16 16 (constantly [0.8 0.4 0.2]))]
    (testing "a cube maps colours (trilinear between its points)"
      (is (= [51 102 204] (render-at img [{:cube {:n 5 :data (swap-cube 5)} :opacity 1.0 :enabled true}] 8 8))))
    (testing "per-channel curves: each channel its own"
      (let [c (float-array (* 3 4096))]
        ;; red: identity, green: 0, blue: 1
        (dotimes [i 4096] (aset c i (float (/ i 4095.0))) (aset c (+ 8192 i) (float 1.0)))
        (is (= [204 0 255] (render-at img [{:curves c :opacity 1.0 :enabled true}] 8 8)))))
    (testing "a radial weight leaves the centre and reaches the corners"
      (let [l [{:cube {:n 2 :data (swap-cube 2)} :radial [8 8 8 8 0.5 0.5] :opacity 1.0 :enabled true}]]
        (is (= [204 102 51] (render-at img l 8 8)))
        (is (= [51 102 204] (render-at img l 0 0)))))
    (px/free-image! img)))

;; test_lut3d_bounds.c: a table's size bounds every read of it. The cube's
;; edges (inputs at 0 and 1) read its last points, nothing past them, and a
;; cube too small to interpolate is ignored rather than read.
(deftest cube-bounds
  (let [corners (filled 2 2 (fn [x y] (if (= [x y] [0 0]) [0 0 0] [1 1 1])))
        l (fn [n data] [{:cube {:n n :data data} :opacity 1.0 :enabled true}])]
    (testing "black and white map to the cube's first and last points"
      (is (= [255 255 255] (render-at corners (l 5 (doto (swap-cube 5) (aset 0 (float 1.0)) (aset 1 (float 1.0)) (aset 2 (float 1.0)))) 0 0)))
      (let [c (swap-cube 3) last (- (alength c) 3)]
        (dotimes [k 3] (aset c (+ last k) (float 0.0)))
        (is (= [0 0 0] (render-at corners (l 3 c) 1 1)))))
    (testing "a one-point cube is not interpolated"
      (is (= [255 255 255] (render-at corners (l 1 (float-array [0 0 0])) 1 1))))
    (px/free-image! corners)))

(deftest transformed
  (let [img (filled 4 2 (fn [x y] [(/ x 3.0) (/ y 1.0) 0]))]
    (testing "an affine view: a quarter turn shows the bottom-left first"
      ;; screen (0,0) -> source (0.5, 1.5): xf = [0 1 0 -1 0 2] on centres
      (let [out (ffi/alloc 4)]
        (px/render! img [] {:xf [0.0 1.0 0.0 -1.0 0.0 2.0] :out out :out-w 1 :out-h 1})
        (is (= [0 255 0] (mapv #(bit-and 0xff %) (ffi/read-array out 3))))
        (ffi/free out)))
    (testing "outside the clip is background"
      (let [out (ffi/alloc 4)]
        (px/render! img [] {:xf [1.0 0.0 0.0 0.0 1.0 0.0] :clip [1 0 3 2] :bg [9 9 9 255]
                            :out out :out-w 1 :out-h 1})
        (is (= [9 9 9] (mapv #(bit-and 0xff %) (ffi/read-array out 3))))
        (ffi/free out)))
    (testing "the histogram counts only the clip"
      (is (= 6 (reduce + (:r (px/histogram img [] [1 0 3 2]))))))
    (testing "export writes the transformed size"
      (let [path (str (System/getProperty "java.io.tmpdir") "/almaz-xf-test.png")]
        (px/save-image! img [] {:path path :format :png8 :quality 90
                                :xf [0.0 1.0 0.0 -1.0 0.0 2.0] :size [2 4]})
        (let [back (px/load-image path)]
          (is (= [2 4] [(px/width back) (px/height back)]))
          (is (every? true? (map (partial close? 0.01) [0 1 0] (px/pixel back 0 0))))
          (px/free-image! back))
        (.delete (java.io.File. path))))
    (px/free-image! img)))

(deftest histograms
  (let [img (filled 64 32 (fn [x _] (if (< x 16) [0 0 0] [1 0.5 0])))
        {:keys [r g b l]} (px/histogram img [])]
    (testing "256 bins per channel, every pixel counted once"
      (is (every? #(= 256 (count %)) [r g b l]))
      (is (every? #(= (* 64 32) (reduce + %)) [r g b l])))
    (testing "each channel's tones land in their bins"
      (is (= (* 16 32) (nth r 0)))
      (is (= (* 48 32) (nth r 255)))
      (is (= (* 48 32) (nth g 128)))
      (is (= (* 64 32) (nth b 0))))
    (testing "the adjustment layers on top are included"
      (let [h (px/histogram img [{:tone (merge neutral {:saturation -1.0}) :opacity 1.0 :enabled true}])]
        (is (= (* 48 32) (reduce + (subvec (:b h) 1))))))
    (px/free-image! img)))

(deftest pyramid
  (testing "zoomed out, the render averages (no aliasing)"
    (let [checker (filled 64 64 (fn [x y] (if (even? (+ x y)) [1 1 1] [0 0 0])))
          v (render-1px checker [])]
      (is (close? 2 127.5 (first v)))
      (px/free-image! checker))))

(deftest masks
  (let [m (px/new-mask 400 400 4 0.0)]
    (px/stroke! m [200 200] [200 200] {:radius 80 :hardness 0.5 :flow 1.0})
    (testing "the brush core is solid and falls off smoothly to nothing"
      (is (close? 1e-3 1.0 (px/mask-at m 200 200)))
      (let [profile (mapv #(px/mask-at m (+ 200 %) 200) (range 0 120 8))]
        (is (apply >= profile) "monotonic falloff")
        (is (some #(< 0.1 % 0.9) profile) "a soft edge, not a step")
        (is (close? 1e-3 0.0 (last profile)))))
    (testing "feathering widens the transition"
      (let [edge #(count (filter (fn [v] (< 0.05 v 0.95))
                                 (map (fn [d] (px/mask-at m (+ 200 d) 200)) (range 0 200 2))))
            before (edge)]
        (px/mask-feather! m 40.0)
        (is (> (edge) before))))
    (testing "erasing removes, invert flips"
      (px/stroke! m [200 200] [200 200] {:radius 30 :hardness 0.9 :flow 1.0 :erase? true})
      (is (< (px/mask-at m 200 200) 0.6))
      (px/mask-fill! m 0.0)
      (px/mask-invert! m)
      (is (close? 1e-4 1.0 (px/mask-at m 10 10))))
    (px/free-mask! m)))

(deftest tiles-are-seamless
  (testing "writing every tile of an image back, in any order, reproduces it"
    (let [rng (java.util.Random. 7)
          w 300 h 220 block 128 overlap 32
          src (filled w h (fn [x y] [(+ 0.5 (* 0.4 (Math/sin (/ x 9.0)))) (/ y (double h))
                                     (+ 0.5 (gauss rng 0.1))]))
          dst (px/new-image w h)
          g (tile/grid w h block overlap)
          buf (ffi/alloc (* 4 3 block block))]
      (loop [order (shuffle g) done #{}]
        (when-let [t (first order)]
          (px/tile-extract! src [] t buf)
          (px/tile-write! dst buf t (tile/ramps g t done))
          (recur (rest order) (conj done (:i t)))))
      (let [diffs (for [y (range 0 h 7) x (range 0 w 7)]
                    (apply max (map #(Math/abs (- %1 %2)) (px/pixel src x y) (px/pixel dst x y))))]
        (is (< (apply max diffs) 1e-4)))
      (ffi/free buf)
      (px/free-image! src)
      (px/free-image! dst))))

(deftest tile-extract-composites
  (testing "a tile reads the composite of the layers below"
    (let [black (filled 16 16 (constantly [0 0 0]))
          white (filled 16 16 (constantly [1 1 1]))
          buf (ffi/alloc (* 4 3 16 16))]
      (px/tile-extract! black [{:img white :opacity 0.25 :enabled true}] {:x 0 :y 0 :w 16 :h 16} buf)
      (is (close? 1e-3 0.25 (first (ffi/read-array buf :float 1))))
      (ffi/free buf)
      (px/free-image! black)
      (px/free-image! white))))

(deftest estimator
  (testing "noise estimate tracks the added noise"
    (let [rng (java.util.Random. 1)
          clean (filled 256 256 (fn [x _] (repeat 3 (+ 0.3 (* 0.4 (/ x 256.0))))))
          noisy (filled 256 256 (fn [x _] (repeat 3 (+ 0.3 (* 0.4 (/ x 256.0)) (gauss rng 0.03)))))
          a (px/estimate clean) b (px/estimate noisy)]
      (is (< (:noise a) 0.005))
      (is (close? 0.01 0.03 (:noise b)))
      (px/free-image! clean)
      (px/free-image! noisy))))

(deftest file-io
  (let [img (filled 40 30 (fn [x y] [(/ x 40.0) (/ y 30.0) 0.25]))
        dir (str (or (System/getenv "TMPDIR") "/tmp/") "almaz-test-" (System/currentTimeMillis))]
    (.mkdirs (java.io.File. dir))
    (doseq [[fmt ext eps] [[:png16 "png" 1e-4] [:tiff16 "tif" 1e-4] [:png8 "png" 0.004] [:jpeg "jpg" 0.03]]]
      (testing (name fmt)
        (let [path (str dir "/out-" (name fmt) "." ext)]
          (px/save-image! img [] {:path path :format fmt :quality 95})
          (let [back (px/load-image path)]
            (is (= [40 30] [(px/width back) (px/height back)]))
            (is (every? true? (map (partial close? eps) (px/pixel img 20 15) (px/pixel back 20 15))))
            (px/free-image! back)))))
    (testing "the file appears only once it's complete: written aside, then renamed"
      (let [path (str dir "/atomic.png")]
        (spit path "previous export")
        ;; the side file can't be created, so the write fails before the rename
        (.mkdirs (java.io.File. (str path ".part")))
        (is (thrown? Exception (px/save-image! img [] {:path path :format :png16 :quality 95})))
        (is (= "previous export" (slurp path)) "a failed export leaves the old file alone")
        (.delete (java.io.File. (str path ".part")))
        (px/save-image! img [] {:path path :format :png16 :quality 95})
        (is (= [40 30] (let [b (px/load-image path)] (try [(px/width b) (px/height b)] (finally (px/free-image! b))))))
        (is (not (.exists (java.io.File. (str path ".part")))))))
    (testing "a missing file is an error, not a crash"
      (is (thrown? Exception (px/load-image (str dir "/nope.png")))))
    (px/free-image! img)))

(def sample-meta
  {:make "NIKON CORPORATION" :model "NIKON Z 9" :lens "NIKKOR Z 180-600mm f/5.6-6.3 VR"
   :taken "2026:10:10 11:30:31" :exposure 0.0005 :fnumber 8.0 :iso 5000
   :focal 480.0 :focal35 480 :orientation 1})

(defn- meta-close? [a b]
  (and (= (dissoc a :exposure :fnumber :focal) (dissoc b :exposure :fnumber :focal))
       (every? #(< (Math/abs (- (double (% a)) (double (% b)))) 1e-6) [:exposure :fnumber :focal])))

(deftest metadata-survives-export
  (let [img (filled 16 12 (constantly [0.5 0.5 0.5]))
        dir (str (or (System/getenv "TMPDIR") "/tmp/") "almaz-meta-" (System/currentTimeMillis))]
    (.mkdirs (java.io.File. dir))
    (doseq [[fmt ext] [[:jpeg "jpg"] [:png16 "png"] [:png8 "png"] [:tiff16 "tif"]]]
      (testing (name fmt)
        (let [path (str dir "/m-" (name fmt) "." ext)]
          (px/save-image! img [] {:path path :format fmt :quality 90 :meta sample-meta})
          (let [back (px/read-meta path)]
            (is (meta-close? sample-meta back) (pr-str back))))))
    (testing "no metadata in, none out (and reading it is not an error)"
      (let [path (str dir "/bare.jpg")]
        (px/save-image! img [] {:path path :format :jpeg :quality 90})
        (is (nil? (px/read-meta path)))))
    (testing "partial metadata writes what it has"
      (let [path (str dir "/partial.jpg")]
        (px/save-image! img [] {:path path :format :jpeg :quality 90 :meta {:make "X" :iso 100}})
        (is (= {:make "X" :iso 100} (select-keys (px/read-meta path) [:make :iso])))))
    (px/free-image! img)))

(deftest native-abi
  (testing "the library matches the struct layouts this code writes"
    (is (nil? (px/abi-problem))))
  (testing "a mismatch names both versions and the fix"
    (let [msg (px/abi-problem (inc px/abi))]
      (is (re-find #"rebuild" msg))
      (is (re-find (re-pattern (str (inc px/abi))) msg)))))

;; ---- GPU ----------------------------------------------------------------------
;; The GPU renderer must draw what the CPU one draws (to rounding): every
;; layer kind, masks, the overlay, a turned and clipped view, zoomed in and out.
(defn- render-both
  "[gpu cpu] RGBA bytes of the same render."
  [img layers opts]
  (let [{:keys [out-w out-h]} opts
        go (fn [gpu?]
             (px/set-gpu! gpu?)
             (let [out (ffi/alloc (* 4 out-w out-h))]
               (try (px/render! img layers (assoc opts :out out))
                    (vec (ffi/read-array out (* 4 out-w out-h)))
                    (finally (ffi/free out)))))]
    (try [(go true) (go false)] (finally (px/set-gpu! true)))))

(defn- max-diff* [a b] (reduce max 0 (map #(Math/abs (- (bit-and 0xff %1) (bit-and 0xff %2))) a b)))

(deftest gpu-matches-cpu
  (when (px/gpu?)
    (let [w 300 h 200
          img (filled w h (fn [x y] [(/ x (double w)) (/ y (double h)) (* 0.5 (+ 0.5 (Math/sin (* 0.1 (+ x y)))))]))
          over (filled w h (fn [x _] [(if (even? (quot x 7)) 0.9 0.1) 0.3 0.6]))
          m (doto (px/new-mask w h 4 0.0) (px/mask-feather! 6.0))
          _ (px/stroke! m [80 100] [220 100] {:radius 50 :hardness 0.5})
          curves (let [c (float-array (* 3 4096))]
                   (dotimes [i 4096] (dotimes [ch 3] (aset c (+ (* ch 4096) i) (float (Math/pow (/ i 4095.0) (+ 0.6 (* 0.3 ch))))))) c)
          layers [{:img over :mask m :opacity 0.7 :enabled true}
                  {:curves curves :opacity 1.0 :enabled true}
                  {:tone {:exposure 0.3 :contrast 0.2 :saturation 0.4} :opacity 0.8 :enabled true}
                  {:cube {:n 9 :data (swap-cube 9)} :radial [150 100 150 100 0.3 0.9] :opacity 1.0 :enabled true}
                  {:img over :opacity 0.3 :enabled false}]]
      (testing "zoomed out, every kind of layer, the mask overlay shown"
        (let [[g c] (render-both img layers {:scale 0.6 :ox 5 :oy 3 :out-w 190 :out-h 130 :bg [7 8 9 255]
                                             :overlay m :tint [1.0 0.25 0.2 0.45]})]
          (is (<= (max-diff* g c) 2) (str "max diff " (max-diff* g c)))))
      (testing "zoomed in (nearest), turned a quarter and clipped"
        (let [[g c] (render-both img layers {:xf [0.0 0.25 40.0 -0.25 0.0 170.0] :clip [50 20 200 160]
                                             :out-w 160 :out-h 120 :bg [7 8 9 255]})]
          (is (<= (max-diff* g c) 2) (str "max diff " (max-diff* g c)))))
      (testing "a pixel change shows on the next render (the GPU copy is refreshed)"
        (let [[g1] (render-both img [] {:scale 1.0 :ox 0 :oy 0 :out-w 4 :out-h 4})]
          (px/set-pixels! img (float-array (* 3 w h) 1.0))
          (let [[g2 c2] (render-both img [] {:scale 1.0 :ox 0 :oy 0 :out-w 4 :out-h 4})]
            (is (not= g1 g2))
            (is (= g2 c2)))))
      (px/free-mask! m)
      (doseq [i [img over]] (px/free-image! i)))))

(deftest gpu-histogram-matches-cpu
  (when (px/gpu?)
    (let [w 640 h 480
          img (filled w h (fn [x y] [(/ x (double w)) (/ y (double h)) (* 0.5 (+ 0.5 (Math/sin (* 0.05 (+ x y)))))]))
          layers [{:tone {:exposure 0.4 :contrast 0.3 :saturation 0.2} :opacity 0.9 :enabled true}
                  {:cube {:n 9 :data (swap-cube 9)} :radial [320 240 320 240 0.2 1.0] :opacity 1.0 :enabled true}]
          both (fn [clip] (px/set-gpu! true)
                 (let [g (px/histogram img layers clip)]
                   (px/set-gpu! false)
                   (let [c (px/histogram img layers clip)] (px/set-gpu! true) [g c])))]
      (doseq [clip [nil [100 50 300 200]]]
        (let [[g c] (both clip)]
          (testing (str "same bins, clip " clip)
            (doseq [k [:r :g :b :l]]
              (is (= (reduce + (k c)) (reduce + (k g))) (str k " counts"))
              ;; to rounding: a value on a bin edge may land in its neighbour, so
              ;; the cumulative counts agree closely at every bin
              (let [cum (fn [v] (reductions + v))
                    worst (reduce max (map #(Math/abs (- %1 %2)) (cum (k g)) (cum (k c))))]
                (is (<= worst (* 0.005 (reduce + (k c)))) (str k " bins: " worst)))))))
      (px/free-image! img))))

;; ---- repair (inpainting) ------------------------------------------------------
(defn- planar
  "Planar RGB floats of f(x, y) over w x h."
  [w h f]
  (let [a (float-array (* 3 w h)) n (* w h)]
    (dotimes [y h] (dotimes [x w]
      (let [[r g b] (f x y) i (+ x (* y w))]
        (aset a i (float r)) (aset a (+ n i) (float g)) (aset a (+ (* 2 n) i) (float b)))))
    a))

(defn- hole-in [w h pred]
  (let [a (byte-array (* w h))]
    (dotimes [y h] (dotimes [x w] (when (pred x y) (aset a (+ x (* y w)) (byte 1)))))
    a))

(defn- at [^floats a w h x y] (let [n (* w h) i (+ x (* y w))] [(aget a i) (aget a (+ n i)) (aget a (+ (* 2 n) i))]))

(deftest inpaint
  (let [w 96 h 80
        disc (fn [x y] (< (+ (Math/pow (- x 48) 2) (Math/pow (- y 40) 2)) 144))]
    (testing "a smooth gradient: the hole continues it, the rest is untouched"
      (let [src (planar w h (fn [x y] [(/ x 95.0) (/ y 79.0) 0.5]))
            ;; something bright in the hole that must go
            src (let [a (aclone src)] (dotimes [y h] (dotimes [x w] (when (disc x y) (dotimes [c 3] (aset a (+ (* c w h) x (* y w)) (float 1.0)))))) a)
            out (px/inpaint src w h (hole-in w h disc) {:seed 1})]
        (is (every? true? (map (partial close? 1e-6) (at src w h 5 5) (at out w h 5 5))) "outside the hole")
        (doseq [[x y] [[48 40] [40 35] [55 46]]]
          (is (every? true? (map (partial close? 0.06) [(/ x 95.0) (/ y 79.0) 0.5] (at out w h x y)))
              (str "inside at " [x y] ": " (at out w h x y))))))
    (testing "a texture: vertical stripes carry on through the hole"
      (let [stripe (fn [x _] (if (< (mod x 8) 4) [0.9 0.9 0.9] [0.1 0.1 0.1]))
            src (planar w h stripe)
            out (px/inpaint src w h (hole-in w h disc) {:seed 1})
            ok (count (for [y (range 32 48) x (range 40 56)
                            :when (< (Math/abs (- (first (at out w h x y)) (first (stripe x y)))) 0.3)] 1))]
        (is (> ok (* 0.8 16 16)) (str ok " of 256 pixels follow the stripes"))))
    (testing "two runs of the same repair agree (threads may vary the detail, not the result)"
      (let [src (planar w h (fn [x y] [(+ 0.5 (* 0.4 (Math/sin (* 0.3 x)))) (+ 0.5 (* 0.4 (Math/cos (* 0.2 y)))) 0.4]))
            hole (hole-in w h disc)
            a (px/inpaint src w h hole {:seed 3}) b (px/inpaint src w h hole {:seed 3})
            n (alength a)
            diff (/ (reduce + (map #(Math/abs (- (aget a %) (aget b %))) (range n))) n)]
        (is (< diff 0.01) (str "mean difference " diff))))
    (testing "a large hole in random texture under a lighting gradient stays in range (the healing once drifted)"
      (let [w 220 h 220 rng (java.util.Random. 5)
            src (planar w h (fn [x y] (let [light (+ 0.2 (* 0.5 (/ x 219.0))) t (* 0.15 (.nextDouble rng))]
                                       [(+ light t) (+ (* 0.8 light) t) (+ (* 0.6 light) (* 0.5 t))])))
            big (fn [x y] (< (+ (Math/pow (- x 110) 2) (Math/pow (- y 110) 2)) 3600))
            out (px/inpaint src w h (hole-in w h big) {:seed 2})
            inside (for [y (range h) x (range w) :when (big x y)] [x y])
            red (map (fn [[x y]] (first (at out w h x y))) inside)]
        (is (<= 0.15 (apply min red)) (str "min " (apply min red)))
        (is (<= (apply max red) 0.9) (str "max " (apply max red)))
        (testing "and its light follows the gradient across it"
          (let [mean (fn [xs] (/ (reduce + xs) (count xs)))
                col (fn [cx] (mean (for [y (range 95 125)] (first (at out w h cx y)))))]
            (is (< (Math/abs (- (col 70) (+ 0.2 (* 0.5 (/ 70 219.0)) 0.075))) 0.08) (str "left " (col 70)))
            (is (< (Math/abs (- (col 150) (+ 0.2 (* 0.5 (/ 150 219.0)) 0.075))) 0.08) (str "right " (col 150)))))))
    (testing "the fill keeps the texture's grain (not a smooth blur)"
      (let [w 160 h 160 rng (java.util.Random. 9)
            src (planar w h (fn [_ _] (let [v (+ 0.3 (* 0.4 (.nextDouble rng)))] [v v v])))
            hole? (fn [x y] (< (+ (Math/pow (- x 80) 2) (Math/pow (- y 80) 2)) 900))
            out (px/inpaint src w h (hole-in w h hole?) {:seed 4})
            grain (fn [pts] (let [ds (for [[x y] pts] (Math/abs (- (first (at out w h x y)) (first (at out w h (inc x) y)))))]
                              (/ (reduce + ds) (count ds))))
            inside (for [y (range 60 100) x (range 60 100) :when (and (hole? x y) (hole? (inc x) y))] [x y])
            outside (for [y (range 5 40) x (range 5 40)] [x y])]
        (is (> (grain inside) (* 0.6 (grain outside)))
            (str "grain inside " (grain inside) " outside " (grain outside)))))
    (testing "beside a large smooth area, a hole in texture is filled with texture (not the smooth patches)"
      (let [w 240 h 200 rng (java.util.Random. 11)
            src (planar w h (fn [x _] (if (< x 120)
                                        [0.45 0.45 0.45]
                                        (let [v (+ 0.3 (* 0.3 (.nextDouble rng)))] [v v v]))))
            hole? (fn [x y] (< (+ (Math/pow (- x 165) 2) (Math/pow (- y 100) 2)) 900))
            out (px/inpaint src w h (hole-in w h hole?) {:seed 6})
            grain (fn [pts] (let [ds (for [[x y] pts] (Math/abs (- (first (at out w h x y)) (first (at out w h (inc x) y)))))]
                              (/ (reduce + ds) (count ds))))
            inside (for [y (range 80 120) x (range 145 185) :when (and (hole? x y) (hole? (inc x) y))] [x y])
            textured (for [y (range 5 40) x (range 200 235)] [x y])]
        (is (> (grain inside) (* 0.6 (grain textured)))
            (str "grain inside " (grain inside) " textured " (grain textured)))))
    (testing "no hole: unchanged"
      (let [src (planar w h (fn [x y] [0.3 0.4 0.5]))]
        (is (= (vec src) (vec (px/inpaint src w h (byte-array (* w h)) {:seed 1}))))))))

(deftest mask-regions
  (let [m (px/new-mask 400 300 4 0.0)]
    (testing "an empty mask has none"
      (is (= [] (px/mask-regions m 0.5 0))))
    (px/stroke! m [50 50] [60 50] {:radius 10 :hardness 1.0})
    (px/stroke! m [300 200] [300 210] {:radius 8 :hardness 1.0})
    (testing "each painted blob is a rect around it (source px), apart from the others"
      (let [rs (sort (px/mask-regions m 0.5 0))]
        (is (= 2 (count rs)))
        (let [[[x y w h] [x2 y2]] rs]
          (is (<= 36 x 44) (str rs))
          (is (<= 36 y 44))
          (is (<= 20 w 40))
          (is (<= 280 x2 296))
          (is (<= 185 y2 196)))))
    (testing "a margin grows them, clamped to the image; overlapping ones merge"
      (let [rs (px/mask-regions m 0.5 200)]
        (is (= 1 (count rs)))
        (is (= [0 0 400 300] (first rs)))))
    (testing "the hole of a rect: mask over the threshold at each pixel"
      (let [hole (px/mask-hole m [40 40 30 20] 0.5)]
        (is (= (* 30 20) (alength hole)))
        (is (= 1 (aget hole (+ 15 (* 10 30)))))
        (is (= 0 (aget hole 0)))))
    (px/free-mask! m)))

(deftest mask-snapshots
  (let [m (px/new-mask 64 64 4 0.0)
        snap (px/mask-copy m)]
    (px/stroke! m [32 32] [32 32] {:radius 10 :hardness 1.0})
    (is (> (px/mask-at m 32 32) 0.5))
    (testing "restoring a copy puts the old pixels back"
      (px/mask-restore! m snap)
      (is (< (px/mask-at m 32 32) 0.01)))
    (doseq [x [m snap]] (px/free-mask! x))))

(defn- plan [w h kfn & [vig]]
  (let [n 64 rmax (+ 2.0 (* 0.5 (Math/hypot w h)))]
    {:center [(/ (dec w) 2.0) (/ (dec h) 2.0)] :rmax rmax :vrmax rmax
     :k (vec (for [ch (range 3)] (vec (repeat n (kfn ch)))))
     :vig (when vig (vec (repeat n vig)))}))

(deftest lens-warp
  (let [w 41 h 31
        src (filled w h (fn [x y] [(/ x 40.0) (/ y 30.0) 0.25]))
        dst (px/new-image w h)]
    (testing "k = 1 everywhere: the image unchanged (bicubic is exact on the grid)"
      (px/lens-warp! dst src (plan w h (constantly 1.0)))
      (doseq [[x y] [[0 0] [7 9] [40 30] [20 15]]]
        (is (every? true? (map (partial close? 2e-5) (px/pixel src x y) (px/pixel dst x y))))))
    (testing "k = 0.5: a pixel reads halfway to the centre (a ramp stays a ramp)"
      (px/lens-warp! dst src (plan w h (constantly 0.5)))
      (let [[r g] (px/pixel dst 40 30)]
        (is (close? 2e-3 (/ 30.0 40.0) r))
        (is (close? 2e-3 (/ 22.5 30.0) g))))
    (testing "per channel: red reads further out than green (TCA)"
      (px/lens-warp! dst src (plan w h #(if (= 0 %) 0.9 0.5)))
      (let [[r g] (px/pixel dst 0 15)]
        (is (close? 2e-3 (/ 2.0 40.0) r))
        (is (close? 2e-3 (/ 7.5 30.0) (second (px/pixel dst 20 0))))))
    (testing "vignetting gain multiplies linear light"
      (let [grey (filled w h (constantly [0.5 0.5 0.5]))]
        (px/lens-warp! dst grey (plan w h (constantly 1.0) 2.0))
        (let [lin (fn [v] (if (<= v 0.04045) (/ v 12.92) (Math/pow (/ (+ v 0.055) 1.055) 2.4)))]
          (is (close? 2e-3 (* 2 (lin 0.5)) (lin (first (px/pixel dst 5 5))))))
        (px/free-image! grey)))
    (testing "only the rows asked for"
      (let [black (px/new-image w h)]
        (px/lens-warp! black src (plan w h (constantly 1.0)) 10 12)
        (is (= [0.0 0.0 0.0] (mapv double (px/pixel black 5 9))))
        (is (pos? (first (px/pixel black 5 10))))
        (px/free-image! black)))
    (doseq [i [src dst]] (px/free-image! i))))

(defn- tile-of
  "A planar 3 x w x h float tile with every channel (f x y)."
  [w h f]
  (let [p (ffi/alloc (* 4 3 w h))]
    (dotimes [c 3] (dotimes [y h] (dotimes [x w] (ffi/write p :float (float (f x y)) (* 4 (+ (* c w h) (* y w) x))))))
    p))

(defn- tile-at [p w h c x y] (double (ffi/read p :float (* 4 (+ (* c w h) (* y w) x)))))

(deftest gaussian-blur
  (let [w 101 h 101]
    (testing "a flat tile stays flat"
      (let [t (tile-of w h (constantly 0.4))]
        (px/gauss! t w h 5.0)
        (is (close? 1e-4 0.4 (tile-at t w h 0 0 0)))
        (is (close? 1e-4 0.4 (tile-at t w h 2 50 50)))
        (ffi/free t)))
    (testing "an impulse spreads with the given sigma and keeps its mass"
      (let [t (tile-of w h (fn [x y] (if (= [x y] [50 50]) 1.0 0.0)))
            sigma 6.0]
        (px/gauss! t w h sigma)
        (let [vals (for [y (range h) x (range w)] [x y (tile-at t w h 1 x y)])
              mass (reduce + (map last vals))
              var-x (/ (reduce + (map (fn [[x _ v]] (* v (Math/pow (- x 50) 2))) vals)) mass)]
          (is (close? 0.02 1.0 mass))
          (is (close? 0.15 1.0 (/ (Math/sqrt var-x) sigma)))
          (is (close? 1e-6 (tile-at t w h 1 45 50) (tile-at t w h 1 55 50)) "symmetric"))
        (ffi/free t)))))

(deftest local-contrast
  (let [w 120 h 40]
    (testing "a flat tile has no local contrast to change"
      (let [t (tile-of w h (constantly 0.5))]
        (px/local-contrast! t w h {:sigma 10.0 :amount 0.8})
        (is (close? 1e-4 0.5 (tile-at t w h 0 60 20)))
        (ffi/free t)))
    (testing "positive amount steepens an edge, negative softens it"
      (let [edge (fn [x _] (if (< x 60) 0.3 0.6))
            up (tile-of w h edge) down (tile-of w h edge)]
        (px/local-contrast! up w h {:sigma 10.0 :amount 0.8})
        (px/local-contrast! down w h {:sigma 10.0 :amount -0.5})
        (is (< (tile-at up w h 0 57 20) 0.3) "dark side darker")
        (is (> (tile-at up w h 0 62 20) 0.6) "bright side brighter")
        (is (> (tile-at down w h 0 57 20) 0.3))
        (is (< (tile-at down w h 0 62 20) 0.6))
        (is (close? 1e-3 0.3 (tile-at up w h 0 5 20)) "far from the edge: unchanged")
        (ffi/free up) (ffi/free down)))))

;; Focus over a whole image: clarity then blur, on the GPU when there is one,
;; matching the CPU's RawTherapee gaussian (to rounding and the GPU's halves).
(deftest whole-image-focus
  (let [w 240 h 160
        src (filled w h (fn [x y] [(if (< x 120) 0.2 0.8) (/ y 159.0) (if (< y 80) 0.3 0.6)]))
        run (fn [gpu? params]
              (px/set-gpu! gpu?)
              (let [out (px/new-image w h)]
                (try (px/focus! out src params)
                     (finally (px/set-gpu! true)))
                out))
        diff (fn [a b] (reduce max 0.0 (for [y (range 0 h 3) x (range 0 w 3) c (range 3)]
                                           (Math/abs (- (nth (px/pixel a x y) c) (nth (px/pixel b x y) c))))))]
    (testing "nothing to do copies the image"
      (let [o (run false {:clarity 0.0 :radius 20.0 :blur 0.0})]
        (is (< (diff o src) 1e-4))
        (px/free-image! o)))
    (testing "blur smears the edge, leaves the far side"
      (let [o (run false {:clarity 0.0 :radius 20.0 :blur 6.0})]
        (is (< 0.4 (first (px/pixel o 120 40)) 0.6))
        (is (close? 0.01 0.2 (first (px/pixel o 40 40))))
        (px/free-image! o)))
    (testing "the GPU draws what the CPU does"
      (doseq [params [{:clarity 0.0 :radius 20.0 :blur 6.0}
                      {:clarity 0.6 :radius 15.0 :blur 0.0}
                      {:clarity -0.4 :radius 25.0 :blur 3.0}]]
        (let [g (run true params) c (run false params)]
          (is (< (diff g c) 0.01) (str params))
          (px/free-image! g) (px/free-image! c))))
    (px/free-image! src)))
