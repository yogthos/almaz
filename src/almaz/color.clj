(ns almaz.color
  "Colour adjustments, as pure functions of display (sRGB 0..1) colours,
   and the tables the native renderer draws them with. Pure.

   Each adjustment kind is a function of its settings (`defaults` names
   them). The renderer doesn't call these per pixel: `table` tabulates a
   kind into what almaz.pixels hands the engine, cached by settings:
     :cube    an n^3 RGB -> RGB map (trilinear in the engine), for the
              colour-mixing kinds
     :curves  three per-channel curves of 4096 points, for the separable
              kinds (levels, the vignette's tone)
   The vignette's spatial falloff is a separate weight (`radial`).

   The maths follows darktable (GPL), adapted to display-referred values:
     hue/saturation  colorbalancergb: hue rotation, chroma scale and its
                     vibrance (C *= 1 + v (1 - C^|v|)), here in Oklab LCh
     selective       colorequal: eight hue nodes, the settings interpolated
                     around the hue circle (raised cosines rather than its
                     RBF, which rings into other bands), brightness weighted
                     by HSB saturation so greys stay put
     colour balance  colorbalancergb's shadows/midtones/highlights opacity
                     masks on luminance, with Photoshop-style colour shifts
                     at constant luminance
     levels          rgblevels: black/grey/white per channel, master first
                     (the grey point maps to 0.5, as in Photoshop)
     curves          rgbcurve: points per channel through a monotone cubic
                     (its MONOTONE_HERMITE), RGB then each channel, then a
                     luminance curve that scales the colour at its hue
     vignette        vignette.c: brightness through a raised-cosine falloff
     black & white   channelmixerrgb's grey mix, normalized, plus a toning
                     tint in the midtones
   Gamut: colours pushed outside sRGB lose chroma at constant lightness and
   hue until they fit."
  (:require [clojure.string :as str]))

;; ---- colour spaces --------------------------------------------------------------
(defn srgb->lin ^double [^double v]
  (if (<= v 0.04045) (/ v 12.92) (Math/pow (/ (+ v 0.055) 1.055) 2.4)))

(defn lin->srgb ^double [^double v]
  (cond (<= v 0.0) 0.0
        (<= v 0.0031308) (* v 12.92)
        :else (- (* 1.055 (Math/pow v (/ 1.0 2.4))) 0.055)))

(defn- cbrt ^double [^double x] (if (neg? x) (- (Math/pow (- x) (/ 1.0 3.0))) (Math/pow x (/ 1.0 3.0))))

(defn lin->oklab
  "Linear sRGB -> Oklab (Björn Ottosson)."
  [[r g b]]
  (let [l (cbrt (+ (* 0.4122214708 r) (* 0.5363325363 g) (* 0.0514459929 b)))
        m (cbrt (+ (* 0.2119034982 r) (* 0.6806995451 g) (* 0.1073969566 b)))
        s (cbrt (+ (* 0.0883024619 r) (* 0.2817188376 g) (* 0.6299787005 b)))]
    [(+ (* 0.2104542553 l) (* 0.7936177850 m) (* -0.0040720468 s))
     (+ (* 1.9779984951 l) (* -2.4285922050 m) (* 0.4505937099 s))
     (+ (* 0.0259040371 l) (* 0.7827717662 m) (* -0.8086757660 s))]))

(defn oklab->lin [[L a b]]
  (let [l (+ L (* 0.3963377774 a) (* 0.2158037573 b))
        m (- L (* 0.1055613458 a) (* 0.0638541728 b))
        s (- L (* 0.0894841775 a) (* 1.2914855480 b))
        l (* l l l) m (* m m m) s (* s s s)]
    [(+ (* 4.0767416621 l) (* -3.3077115913 m) (* 0.2309699292 s))
     (+ (* -1.2684380046 l) (* 2.6097574011 m) (* -0.3413193965 s))
     (+ (* -0.0041960863 l) (* -0.7034186147 m) (* 1.7076147010 s))]))

;; (the hot paths spell out the three channels: a table build calls them
;; tens of thousands of times, and mapv over a 3-vector costs more than the
;; maths)
(defn- lin3 [[r g b]] [(srgb->lin r) (srgb->lin g) (srgb->lin b)])
(defn- srgb3 [[r g b]] [(lin->srgb r) (lin->srgb g) (lin->srgb b)])

(defn srgb->oklab [rgb] (lin->oklab (lin3 rgb)))

(defn- clamp01 ^double [^double v] (max 0.0 (min 1.0 v)))

(defn- in-gamut? [[r g b]]
  (and (<= -1e-6 r 1.000001) (<= -1e-6 g 1.000001) (<= -1e-6 b 1.000001)))

(defn lch->lin
  "Oklab LCh -> linear sRGB, losing chroma (at constant L and h) until the
   colour fits."
  [L C h]
  (let [L (clamp01 L)
        ca (Math/cos h) sa (Math/sin h)
        at (fn [c] (oklab->lin [L (* c ca) (* c sa)]))
        rgb (at C)
        [r g b] (if (in-gamut? rgb)
                  rgb
                  (loop [lo 0.0 hi C i 0]
                    (if (= i 14)
                      (at lo)
                      (let [mid (* 0.5 (+ lo hi))]
                        (if (in-gamut? (at mid)) (recur mid hi (inc i)) (recur lo mid (inc i)))))))]
    [(clamp01 r) (clamp01 g) (clamp01 b)]))

(defn oklab->srgb [lab]
  (let [[L a b] lab]
    (srgb3 (lch->lin L (Math/hypot a b) (Math/atan2 b a)))))

(defn- luminance ^double [[r g b]] (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b)))

;; ---- settings -------------------------------------------------------------------
(def bands "selective colour's hue bands" [:red :orange :yellow :green :cyan :blue :purple :magenta])
(def ranges "colour balance's tonal ranges" [:shadows :midtones :highlights])
(def channels "levels' channels: the master, then each" [:rgb :r :g :b])

(defn- k [& parts] (keyword (apply str (map name parts))))

(defn sel-key [band p] (k "sel-" band "-" p))
(defn bal-key [rng p] (k "bal-" rng "-" p))
(defn lv-key [ch p] (k "lv-" ch "-" p))

(def settings
  "Each kind's settings: {key [default min max]}."
  {:hsl {:hs-hue [0.0 -1.0 1.0] :hs-sat [0.0 -1.0 1.0] :hs-vib [0.0 -1.0 1.0]}
   :selective (into {} (for [b bands p [:hue :sat :bri]] [(sel-key b p) [0.0 -1.0 1.0]]))
   :balance (into {} (for [r ranges p [:cr :mg :yb :sat :bri]] [(bal-key r p) [0.0 -1.0 1.0]]))
   :levels (into {} (for [c channels [p d] [[:black 0.0] [:grey 0.5] [:white 1.0]]] [(lv-key c p) [d 0.0 1.0]]))
   :vignette {:vig-exposure [-0.5 -1.0 1.0] :vig-black [0.0 -1.0 1.0] :vig-softness [1.0 0.0 1.0]}
   :bw {:bw-red [0.0 -1.0 1.0] :bw-green [0.0 -1.0 1.0] :bw-blue [0.0 -1.0 1.0]
        :bw-tone [0.0 -1.0 1.0] :bw-intensity [1.0 0.0 1.0]}})

;; Curves keep a list of points per channel, as text ("x,y;x,y", "" for the
;; straight line): not numeric settings
(def curve-channels "curves' channels, as the menu lists them" [:l :rgb :r :g :b])
(defn cv-key [ch] (k "cv-" ch))
(def curve-settings {:curves (into {} (for [c curve-channels] [(cv-key c) ""]))})

(def kinds "Every colour adjustment kind." (into (set (keys settings)) (keys curve-settings)))

(defn setting-keys "Kind `kind`'s setting keys." [kind] (keys (or (settings kind) (curve-settings kind))))

(defn defaults [kind]
  (or (curve-settings kind) (into {} (map (fn [[k [d]]] [k d])) (settings kind))))

(declare parse-points)

(def ^:private straight [[0.0 0.0] [1.0 1.0]])

(defn- neutral?
  "Do settings `p` leave every colour as it is? (Black & white never does;
   a vignette does at no exposure and black point, whatever its softness.)"
  [kind p]
  (case kind
    :bw false
    :curves (every? #(= straight (parse-points (get p %))) (setting-keys :curves))
    :vignette (and (zero? (double (:vig-exposure p 0.0))) (zero? (double (:vig-black p 0.0))))
    (every? (fn [[k d]] (== d (double (get p k d)))) (defaults kind))))

;; ---- hue / saturation -----------------------------------------------------------
(defn- vibrance-factor ^double [^double C ^double v]
  ;; colorbalancergb: v (1 - C^|v|), chroma normalized to sRGB's reach
  (if (zero? v) 0.0 (* v (- 1.0 (Math/pow (min 1.0 (/ C 0.32)) (Math/abs v))))))

(defn- hsl-fn [{:keys [hs-hue hs-sat hs-vib]}]
  (let [dh (* Math/PI hs-hue)]
    (fn [rgb]
      (let [[L a b] (srgb->oklab rgb)
            C (Math/hypot a b)
            h (+ (Math/atan2 b a) dh)
            C (* C (max 0.0 (+ 1.0 hs-sat (vibrance-factor C hs-vib))))]
        (srgb3 (lch->lin L C h))))))

;; ---- selective colour -----------------------------------------------------------
;; colorequal interpolates its eight hue nodes with a periodic RBF; that
;; rings (a red-only change moves blues by a few percent), so here each hue
;; blends its two neighbouring nodes with raised-cosine weights instead
;; (cos^2 + sin^2 = 1): exact at a node, nothing past the next one. The nodes
;; sit at the Oklab hues of the swatches the panel shows.
(def band-colours
  "The swatch colour of each band (sRGB)."
  {:red [1.0 0.0 0.0] :orange [1.0 0.5 0.0] :yellow [1.0 1.0 0.0] :green [0.0 1.0 0.0]
   :cyan [0.0 1.0 1.0] :blue [0.0 0.0 1.0] :purple [0.5 0.0 1.0] :magenta [1.0 0.0 1.0]})

(def ^:private two-pi (* 2.0 Math/PI))

(def ^:private node-hues
  (mapv (fn [b] (let [[_ a bb] (srgb->oklab (band-colours b))] (mod (Math/atan2 bb a) two-pi))) bands))

(defn- interpolate-hue
  "The node `values` interpolated at hue h (radians)."
  [values h]
  (let [n (count node-hues)
        h (mod h two-pi)
        ;; the node at or below h, going round
        i (or (last (filter #(<= (node-hues %) h) (range n))) (dec n))
        j (mod (inc i) n)
        span (mod (- (node-hues j) (node-hues i)) two-pi)
        t (/ (mod (- h (node-hues i)) two-pi) span)
        c (Math/cos (* 0.5 Math/PI t))
        w (* c c)]
    (+ (* w (values i)) (* (- 1.0 w) (values j)))))

(def ^:private hue-lut-n 1440)

(defn- hue-curve
  "interpolate-hue's curve tabulated round the circle, or nil when all 0."
  [values]
  (when-not (every? zero? values)
    (let [t (double-array (inc hue-lut-n))]
      (dotimes [i (inc hue-lut-n)]
        (aset t i (double (interpolate-hue values (/ (* two-pi i) hue-lut-n)))))
      t)))

(defn- at-hue ^double [t ^double h]
  (if (nil? t)
    0.0
    (let [f (* (/ (mod h two-pi) two-pi) hue-lut-n)
          i (min (dec hue-lut-n) (long f))
          a (aget ^doubles t i)]
      (+ a (* (- f i) (- (aget ^doubles t (inc i)) a))))))

(defn- selective-fn [p]
  (let [curve (fn [q] (hue-curve (mapv #(double (get p (sel-key % q) 0.0)) bands)))
        hue (curve :hue) sat (curve :sat) bri (curve :bri)
        quarter (/ Math/PI 4.0)]
    (fn [rgb]
      (let [[L a b] (srgb->oklab rgb)
            C (Math/hypot a b)]
        (if (< C 1e-7)
          rgb
          (let [h (Math/atan2 b a)
                ;; HSB saturation: how far from grey, 0 for greys (colorequal)
                mx (apply max rgb) S (if (pos? mx) (/ (- mx (apply min rgb)) mx) 0.0)
                h2 (+ h (* quarter (at-hue hue h)))
                C2 (* C (max 0.0 (+ 1.0 (at-hue sat h))))
                L2 (* L (max 0.0 (+ 1.0 (* 0.25 S (at-hue bri h)))))]
            (srgb3 (lch->lin L2 C2 h2))))))))

;; ---- colour balance -------------------------------------------------------------
;; colorbalancergb's opacity masks on x = Y^0.41 (middle grey at 0.5).
(def ^:private mask-exp 0.4101205819200422)
(def ^:private fulcrum (Math/pow 0.1845 mask-exp))

(defn range-weights
  "[shadows midtones highlights] opacities for linear luminance Y."
  [Y]
  (let [x (Math/pow (max 0.0 Y) mask-exp)
        xo (- x fulcrum) xn (/ xo fulcrum)
        sw 4.0 hw 4.0 mw (/ (* sw sw hw hw) (+ (* sw sw) (* hw hw)))
        alpha (/ 1.0 (+ 1.0 (Math/exp (* xn sw))))
        beta (/ 1.0 (+ 1.0 (Math/exp (- (* xn hw)))))
        a1 (- 1.0 alpha) b1 (- 1.0 beta)
        gamma (* (Math/exp (- (/ (* xo xo mw) 4.0))) a1 a1 b1 b1 8.0)]
    [alpha gamma beta]))

(def ^:private ln2 (Math/log 2.0))

(defn- balance-fn [p]
  (let [v (fn [q] (mapv #(double (get p (bal-key % q) 0.0)) ranges))
        cr (v :cr) mg (v :mg) yb (v :yb) sat (v :sat) bri (v :bri)
        dot (fn [[w0 w1 w2] [x0 x1 x2]] (+ (* w0 x0) (* w1 x1) (* w2 x2)))
        ev (fn [x] (Math/exp (* ln2 x)))]
    (fn [rgb]
      (let [[r g b :as lin] (lin3 rgb)
            Y (luminance lin)
            ws (range-weights Y)
            ;; each slider pulls its channel up (or its complement), ~1 EV at
            ;; full, at constant luminance (Photoshop's "preserve luminosity"),
            ;; then the range's brightness
            r (* r (ev (dot ws cr))) g (* g (ev (dot ws mg))) b (* b (ev (dot ws yb)))
            Y2 (luminance [r g b])
            k (* (if (pos? Y2) (/ Y Y2) 1.0) (ev (dot ws bri)))
            [L a bb] (lin->oklab [(* k r) (* k g) (* k b)])
            s (max 0.0 (+ 1.0 (dot ws sat)))]
        (srgb3 (lch->lin L (* s (Math/hypot a bb)) (Math/atan2 bb a)))))))

;; ---- levels ---------------------------------------------------------------------
(defn level
  "One channel's levels curve at x: black -> 0, white -> 1, grey -> 0.5."
  ^double [^double black ^double grey ^double white ^double x]
  (let [span (- white black)]
    (if (< span 1e-6)
      (if (< x black) 0.0 1.0)
      (let [p (clamp01 (/ (- x black) span))
            pg (min 0.999 (max 0.001 (/ (- grey black) span)))
            g (/ (Math/log 0.5) (Math/log pg))]
        (Math/pow p g)))))

(defn- channel-curve [p ch]
  (let [[b g w] (map #(double (get p (lv-key ch %))) [:black :grey :white])]
    (fn [x] (level b g w x))))

(defn- levels-fn [p]
  (let [master (channel-curve p :rgb)
        [fr fg fb] (mapv #(channel-curve p %) [:r :g :b])]
    (fn [[r g b]] [(fr (master r)) (fg (master g)) (fb (master b))])))

(defn set-level
  "Move channel `ch`'s :black, :grey or :white point to v. Moving black or
   white keeps grey at the same place between them (darktable)."
  [p ch which v]
  (let [b (get p (lv-key ch :black)) g (get p (lv-key ch :grey)) w (get p (lv-key ch :white))
        rel (if (> (- w b) 1e-6) (/ (- g b) (- w b)) 0.5)
        v (clamp01 v)]
    (case which
      :grey (assoc p (lv-key ch :grey) (max (+ b 0.002) (min (- w 0.002) v)))
      :black (let [b (min v (- w 0.01))] (assoc p (lv-key ch :black) b (lv-key ch :grey) (+ b (* rel (- w b)))))
      :white (let [w (max v (+ b 0.01))] (assoc p (lv-key ch :white) w (lv-key ch :grey) (+ b (* rel (- w b))))))))

(defn pick-level
  "Set points from a picked colour [r g b]: black and white per channel (a
   cast in the picked tone is neutralized), grey so the colour comes out
   grey."
  [p which rgb]
  (case which
    (:black :white) (reduce (fn [p [ch v]] (set-level p ch which v)) p (map vector [:r :g :b] rgb))
    :grey (let [master (channel-curve p :rgb)
                ms (mapv master rgb)
                t (/ (reduce + ms) 3.0)]
            (reduce (fn [p [ch m]]
                      (let [b (get p (lv-key ch :black)) w (get p (lv-key ch :white))
                            x (/ (- m b) (- w b))]
                        (if (and (< 0.001 x 0.999) (< 0.001 t 0.999))
                          ;; x^gamma = t with gamma = log 0.5 / log pg
                          (set-level p ch :grey (+ b (* (- w b) (Math/pow 0.5 (/ (Math/log x) (Math/log t))))))
                          p)))
                    p (map vector [:r :g :b] ms)))))

;; ---- curves ---------------------------------------------------------------------
(defn- parse-double* [s] (try (Double/parseDouble (str/trim s)) (catch Exception _ nil)))

(defn parse-points
  "A curve's points from their text (\"x,y;x,y\"), sorted by x, in 0..1, one
   per x; the straight line for none, or fewer than two."
  [s]
  (let [pts (->> (str/split (str s) #";")
                 (keep (fn [pair]
                         (let [[x y & more] (map parse-double* (str/split pair #","))]
                           (when (and x y (nil? more) (== x x) (== y y))
                             [(clamp01 x) (clamp01 y)]))))
                 (sort-by first)
                 (reduce (fn [acc [x :as p]] (if (and (seq acc) (== x (first (peek acc)))) acc (conj acc p))) []))]
    (if (< (count pts) 2) straight pts)))

(defn format-points
  "Points as text for the layer: \"\" for the straight line."
  [pts]
  (if (= straight (mapv (fn [[x y]] [(double x) (double y)]) pts))
    ""
    (str/join ";" (map (fn [[x y]] (str (/ (Math/round (* 1e4 (double x))) 1e4) ","
                                        (/ (Math/round (* 1e4 (double y))) 1e4)))
                       pts))))

(defn- tangents
  "Fritsch-Carlson tangents: a cubic Hermite through the points that never
   overshoots them (darktable curve_tools' monotone hermite)."
  [xs ys]
  (let [n (count xs)
        d (mapv (fn [i] (/ (- (ys (inc i)) (ys i)) (- (xs (inc i)) (xs i)))) (range (dec n)))
        m (transient (vec (for [i (range n)]
                            (cond (zero? i) (d 0)
                                  (= i (dec n)) (d (- n 2))
                                  (pos? (* (d (dec i)) (d i))) (/ (+ (d (dec i)) (d i)) 2.0)
                                  :else 0.0))))]
    (persistent!
     (reduce (fn [m i]
               (let [di (d i)]
                 (if (zero? di)
                   (-> m (assoc! i 0.0) (assoc! (inc i) 0.0))
                   (let [a (/ (m i) di) b (/ (m (inc i)) di) h (+ (* a a) (* b b))]
                     (cond (or (neg? a) (neg? b)) (-> m (assoc! i (if (neg? a) 0.0 (m i)))
                                                      (assoc! (inc i) (if (neg? b) 0.0 (m (inc i)))))
                           (> h 9.0) (let [t (/ 3.0 (Math/sqrt h))]
                                       (-> m (assoc! i (* t a di)) (assoc! (inc i) (* t b di))))
                           :else m)))))
             m (range (dec n))))))

(defn curve-fn
  "The curve through `pts` (sorted by x) as a function of 0..1, flat past
   its end points, in 0..1."
  [pts]
  (let [xs (mapv (comp double first) pts) ys (mapv (comp double second) pts)
        n (count xs) m (tangents xs ys)
        x0 (xs 0) xn (xs (dec n))]
    (fn ^double [^double x]
      (cond
        (<= x x0) (ys 0)
        (>= x xn) (ys (dec n))
        :else (let [i (loop [i 0] (if (and (< i (- n 2)) (>= x (xs (inc i)))) (recur (inc i)) i))
                    h (- (xs (inc i)) (xs i))
                    t (/ (- x (xs i)) h) t2 (* t t) t3 (* t2 t)]
                (clamp01 (+ (* (+ (* 2 t3) (* -3 t2) 1) (ys i))
                            (* (+ t3 (* -2 t2) t) h (m i))
                            (* (+ (* -2 t3) (* 3 t2)) (ys (inc i)))
                            (* (- t3 t2) h (m (inc i))))))))))

(def ^:private min-gap "Closest two points may get in x." 0.01)

(defn add-point
  "Points with [x y] put in by x: [points its index]."
  [pts x y]
  (let [p [(clamp01 x) (clamp01 y)]
        i (count (take-while #(< (first %) (first p)) pts))]
    [(vec (concat (take i pts) [p] (drop i pts))) i]))

(defn move-point
  "Point i moved to [x y], x kept between its neighbours."
  [pts i x y]
  (let [lo (if (pos? i) (+ (first (pts (dec i))) min-gap) 0.0)
        hi (if (< i (dec (count pts))) (- (first (pts (inc i))) min-gap) 1.0)]
    (assoc pts i [(max lo (min hi (clamp01 x))) (clamp01 y)])))

(defn remove-point
  "Point i gone; an end point instead goes back to its corner."
  [pts i]
  (let [n (count pts)]
    (cond (zero? i) (assoc pts 0 [0.0 0.0])
          (= i (dec n)) (assoc pts i [1.0 1.0])
          :else (vec (concat (take i pts) (drop (inc i) pts))))))

(defn point-near
  "The index of the point within r of [x y] (nearest), or nil."
  [pts x y r]
  (let [[i d] (apply min-key second (map-indexed (fn [i [px py]] [i (Math/hypot (- px x) (- py y))]) pts))]
    (when (<= d r) i)))

(defn- curves-fn [p]
  (let [f (fn [ch] (curve-fn (parse-points (get p (cv-key ch)))))
        master (f :rgb)
        [fr fg fb] (mapv f [:r :g :b])
        lum (when-not (= straight (parse-points (get p (cv-key :l)))) (f :l))]
    (fn [[r g b]]
      (let [out [(fr (master r)) (fg (master g)) (fb (master b))]]
        (if-not lum
          out
          ;; the luminance through the curve (in display encoding), the
          ;; colour scaled to it in linear light at its hue
          (let [lin (lin3 out)
                Y (luminance lin)
                Y' (srgb->lin (lum (lin->srgb Y)))]
            (if (< Y 1e-6)
              (let [v (lin->srgb Y')] [v v v])
              (let [[L a bb] (lin->oklab (mapv #(* % (/ Y' Y)) lin))]
                (mapv clamp01 (srgb3 (lch->lin L (Math/hypot a bb) (Math/atan2 bb a))))))))))))

;; ---- vignette -------------------------------------------------------------------
(defn- vignette-curve [{:keys [vig-exposure vig-black]}]
  (fn [^double x]
    (let [x (if (neg? vig-exposure) (* x (+ 1.0 vig-exposure)) (+ x (* vig-exposure (- 1.0 x))))
          bp (* 0.25 (Math/abs (double vig-black)))]
      (cond (pos? vig-black) (clamp01 (/ (- x bp) (- 1.0 bp)))
            (neg? vig-black) (+ x (* bp (- 1.0 x)))
            :else x))))

(defn- vignette-fn [p] (let [f (vignette-curve p)] (fn [[r g b]] [(f r) (f g) (f b)])))

(defn radial
  "The vignette's weight [cx cy rx ry inner falloff] (almaz.pixels) for the
   crop rect [x y w h], source px: an ellipse matching the crop (darktable's
   autoratio), soft from well inside to past the corners, or hard from the
   edges."
  [{:keys [vig-softness]} [x y w h]]
  (let [s (double vig-softness)]
    [(+ x (/ w 2.0)) (+ y (/ h 2.0)) (/ w 2.0) (/ h 2.0)
     (- 1.0 (* 0.7 s)) (+ 0.15 (* 1.05 s))]))

;; ---- black & white --------------------------------------------------------------
(def ^:private rec709 [0.2126 0.7152 0.0722])

(defn- bw-fn [{:keys [bw-red bw-green bw-blue bw-tone bw-intensity]}]
  (let [ws (mapv + rec709 [bw-red bw-green bw-blue])
        sum (reduce + ws)
        [w0 w1 w2] (if (> (Math/abs sum) 0.05) (mapv #(/ % sum) ws) ws)
        hue (Math/toRadians (if (pos? bw-tone) 75.0 255.0))
        amount (* 0.06 (Math/abs (double bw-tone)))
        i (double bw-intensity)
        mix (fn [o t] (lin->srgb (+ o (* i (- t o)))))]
    (fn [rgb]
      (let [[r g b] (lin3 rgb)
            grey (max 0.0 (+ (* w0 r) (* w1 g) (* w2 b)))
            [tr tg tb] (if (pos? amount)
                         (let [L (cbrt grey)] ; Oklab L of a grey
                           (lch->lin L (* amount (min 1.0 (* 4.0 L (- 1.0 L)))) hue))
                         [grey grey grey])]
        [(mix r tr) (mix g tg) (mix b tb)]))))

;; ---- tables ---------------------------------------------------------------------
(defn transform
  "Kind `kind` with settings `p` as a function of an sRGB colour."
  [kind p]
  (let [p (merge (defaults kind) p)]
    (case kind
      :hsl (hsl-fn p)
      :selective (selective-fn p)
      :balance (balance-fn p)
      :levels (levels-fn p)
      :curves (curves-fn p)
      :vignette (vignette-fn p)
      :bw (bw-fn p))))

(def cube-n "Grid points per axis of a colour cube." 33)
(def curve-n "Points per channel of a curve (ALMZ_TONE_N)." 4096)

(def ^:private build-threads 8)

(defn- build-cube
  "f tabulated on an n^3 grid, the blue slices shared out over threads."
  [f n]
  (let [a (float-array (* 3 n n n))
        s (/ 1.0 (dec n))
        slices (fn [b0 b1]
                 (doseq [bi (range b0 b1)]
                   (dotimes [gi n]
                     (dotimes [ri n]
                       (let [[x y z] (f [(* ri s) (* gi s) (* bi s)])
                             i (* 3 (+ ri (* n (+ gi (* n bi)))))]
                         (aset a i (float x))
                         (aset a (+ i 1) (float y))
                         (aset a (+ i 2) (float z)))))))
        step (long (Math/ceil (/ n (double build-threads))))]
    (run! deref (mapv (fn [b0] (future (slices b0 (min n (+ b0 step))))) (range 0 n step)))
    a))

(defn- build-curves
  "Per-channel curves from f on greys: channel c of f([x x x])."
  [f n]
  (let [a (float-array (* 3 n))]
    (dotimes [i n]
      (let [x (/ i (double (dec n)))
            out (f [x x x])]
        (dotimes [c 3] (aset a (+ (* c n) i) (float (nth out c))))))
    a))

(defn- build-table [kind p]
  (when-not (neutral? kind p)
    (let [f (transform kind p)]
      (if (or (#{:levels :vignette} kind)
              (and (= kind :curves) (= straight (parse-points (get p (cv-key :l))))))
        {:curves (build-curves f curve-n)}
        {:cube {:n cube-n :data (build-cube f cube-n)}}))))

(defonce ^:private cache (atom {:order [] :tables {}}))
(def ^:private cache-size 24)

(defn- table-key [kind p] [kind (select-keys p (setting-keys kind))])

(defn cached
  "The table for these settings if it's built, else ::none."
  [kind p]
  (get (:tables @cache) (table-key kind p) ::none))

(defn table
  "What the engine draws kind `kind` with: {:cube {:n :data}} or {:curves},
   or nil when the settings change nothing. Cached by settings (slider drags
   revisit them, and every render asks)."
  [kind p]
  (let [key (table-key kind p)
        {:keys [tables]} @cache]
    (if (contains? tables key)
      (tables key)
      (let [t (build-table kind (second key))]
        (swap! cache (fn [{:keys [order tables]}]
                       (let [order (conj order key)
                             [drop keep] (split-at (max 0 (- (count order) cache-size)) order)]
                         {:order (vec keep) :tables (apply dissoc (assoc tables key t) drop)})))
        t))))
