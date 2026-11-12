(ns almaz.lens
  "Lens correction: which camera and lens took the photo, and how to undo
   that lens's distortion, lateral chromatic aberration (TCA) and vignetting.
   Pure.

   Ported from lensfun (libs/lensfun: database.cpp, lens.cpp, auxfun.cpp,
   modifier.cpp, mod-coord.cpp, mod-subpix.cpp, mod-color.cpp; LGPL-3) as
   darktable's lens module (src/iop/lens.cc) and RawTherapee's
   (rtengine/rtlensfun.cc) drive it, with lensfun's database (CC-BY-SA 3.0,
   almaz.lens.db). Raw files that carry their own correction (Sony's
   DistortionCorrParams & co.) use it instead, as darktable's \"embedded
   metadata\" method does.

   Every correction here is radial about the lens centre, so a plan reduces
   to per-channel tables: for each distance r from the centre in the
   corrected image, the factor k(r) taking a pixel's offset from the centre
   to where it is in the source; and the gain undoing vignetting at a source
   distance. almaz.pixels/lens-warp! applies them.

   The calibrations are in the units of the sensor they were made on (crop
   factor and aspect ratio, the lens's `presets`); lensfun's coefficient
   rescaling carries them over to the photo's camera."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [almaz.lens.db :as db]))

;; ---- strings (auxfun.cpp) -------------------------------------------------------
(defn- norm-str
  "_lf_strcmp's view of a string: case and runs of whitespace ignored, as
   are leading and trailing spaces."
  [s]
  (when s (not-empty (str/lower-case (str/trim (str/replace s #"\s+" " "))))))

(defn str=
  "_lf_strcmp(a, b) == 0."
  [a b] (= (norm-str a) (norm-str b)))

(defn mlstr=
  "_lf_mlstrcmp: does `s` equal any language variant of `ml`?"
  [s ml] (boolean (some #(str= s %) ml)))

(def ^:private punct (set "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"))
(defn- digit? [c] (and c (<= (int \0) (int c) (int \9))))
(defn- punct? [c] (contains? punct c))
(defn- space? [c] (and c (Character/isWhitespace (char c))))

(defn split-words
  "lfFuzzyStrCmp::Split: the words of `s` by character class (a number with
   its dots, a run of punctuation, a run of anything else), \".0\" dropped
   from numbers, solitary punctuation and a lone \"f\" skipped (but not \"*\"
   or \"+\"), lower-cased and sorted."
  [s]
  (loop [i 0 acc []]
    (let [n (count (or s ""))
          i (loop [i i] (if (and (< i n) (space? (nth s i))) (recur (inc i)) i))]
      (if (>= i n)
        (vec (sort acc))
        (let [c (nth s i)
              end (cond
                    (digit? c) (loop [j (inc i)] (if (and (< j n) (or (digit? (nth s j)) (= \. (nth s j)))) (recur (inc j)) j))
                    (punct? c) (loop [j (inc i)] (if (and (< j n) (punct? (nth s j))) (recur (inc j)) j))
                    :else (loop [j (inc i)]
                            (let [d (when (< j n) (nth s j))]
                              (if (and d (not (space? d)) (not (digit? d)) (not (punct? d))) (recur (inc j)) j))))
              w (subs s i end)
              w (if (and (digit? c) (>= (count w) 2) (str/ends-with? w ".0")) (subs w 0 (- (count w) 2)) w)
              skip? (and (= 1 (- end i)) (or (punct? c) (= \f (Character/toLowerCase (char c))))
                         (not= \* c) (not= \+ c))]
          (recur end (if skip? acc (conj acc (str/lower-case w)))))))))

(defn fuzzy-score
  "lfFuzzyStrCmp::Compare of pattern words `pw` (split-words) against the
   string `match`: 0 for no match, up to 100 when both have the same words.
   `all?` requires every pattern word in the match."
  [pw match all?]
  (let [mw (split-words match)
        np (count pw) nm (count mw)]
    (if (or (zero? np) (zero? nm))
      0
      (loop [[p & ps :as pws] pw mi 0 score 0]
        (if (empty? pws)
          (quot (* score 200) (+ np nm))
          (let [old mi
                [mi score fail?]
                (loop [mi mi]
                  (if (>= mi nm)
                    [mi score false]
                    (let [r (compare p (nth mw mi))]
                      (cond (zero? r) [mi (inc score) false]
                            (neg? r) (if all? [mi score true] [(dec old) score false])
                            :else (recur (inc mi))))))]
            (cond
              fail? 0
              all? (if (>= mi nm) 0 (recur ps (inc mi) score))
              :else (recur ps (if (or (neg? mi) (>= mi nm)) old (inc mi)) score))))))))

(defn- ml-fuzzy
  "lfFuzzyStrCmp::Compare over every language variant: the best score."
  [pw ml all?]
  (reduce (fn [best s] (let [v (fuzzy-score pw s all?)] (if (>= v 100) (reduced v) (max best v)))) 0 ml))

;; ---- lens parameters from a name (lens.cpp) -------------------------------------
(def ^:private name-patterns
  "lens_name_regexes with the groups holding [min focal, max focal, min aperture]."
  [[#"[^:]*?([0-9]+[0-9.]*)[-]?([0-9]+[0-9.]*)?(mm)\s+(f/|f|1/|1:)?([0-9.]+)(-[0-9.]+)?.*" [1 2 5]]
   [#".*?1:([0-9.]+)[-]?([0-9.]+)?\s+([0-9.]+)[-]?([0-9.]+)?(mm)?.*" [3 4 1]]
   [#".*?([0-9.]+)[-]?([0-9.]+)?\s*/\s*([0-9.]+)[-]?([0-9.]+)?.*" [3 4 1]]])

(def ^:private not-a-lens ["adapter" "reducer" "booster" "extender" "converter" "magnifier"])

(defn- atof [s] (try (Double/parseDouble s) (catch Exception _ 0.0)))

(defn- from-name
  "[min-focal max-focal min-aperture] read from a lens model name, nil each
   when not found."
  [model]
  (when (and model (not-any? #(str/includes? model %) not-a-lens)
             (not (re-matches #".*?[0-9](\.[0.9]+)?x.*" model)))
    (some (fn [[re idx]]
            (when-let [m (re-matches re model)]
              (mapv #(some-> (nth m %) atof) idx)))
          name-patterns)))

(defn guess-parameters
  "lfLens::GuessParameters: fill a lens's missing focal and aperture range
   from its name, else from its calibrations."
  [{:keys [model calibs] [min-f max-f] :focal [min-a max-a] :aperture :as lens}]
  (let [min-f (or min-f 0.0) max-f (or max-f 0.0) min-a (or min-a 0.0) max-a (or max-a 0.0)
        need? (or (zero? min-a) (zero? min-f))
        ;; nil = lensfun's "not found" sentinels; the name and then every
        ;; calibration narrow them
        [nf xf na] (when need? (from-name (first model)))
        lo (fn [a b] (if (and a b) (min a b) (or a b)))
        hi (fn [a b] (if (and a b) (max a b) (or a b)))
        focals (when need? (for [c calibs k [:distortion :tca :vignetting] e (get c k)] (double (nth e 1))))
        aps (when need? (for [c calibs e (:vignetting c)] (double (nth e 2))))
        minf (reduce lo nf focals) maxf (reduce hi xf focals)
        mina (reduce lo na aps) maxa (reduce hi nil aps)
        min-f' (if (and minf (zero? min-f)) minf min-f)
        max-f' (if (and maxf (zero? max-f)) maxf max-f)]
    (assoc lens
           :focal [min-f' (if (zero? max-f') min-f' max-f')]
           :aperture [(if (and mina (zero? min-a)) mina min-a) (if (and maxa (zero? max-a)) maxa max-a)])))

(defn- valid-lens?
  "lfLens::Check, after guessing."
  [{:keys [model mounts calibs crop aspect] [mnf mxf] :focal [mna mxa] :aperture}]
  (and (seq model) (seq mounts) (<= mnf mxf)
       (or (zero? mxa) (<= mna mxa))
       (every? #(and (pos? (:crop %)) (>= (:aspect %) 1.0)) calibs)
       (pos? crop) (>= aspect 1.0)))

;; ---- the database --------------------------------------------------------------
(defonce ^:private database
  (delay (let [{:keys [lenses] :as d} (edn/read-string db/edn)]
           (assoc d :lenses (vec (filter valid-lens? (map guess-parameters lenses)))))))

(defn database* "The parsed lensfun database." [] @database)

(defn- score-sort [xs] (map first (sort-by (comp - second) xs)))

(defn find-cameras
  "lfDatabase::FindCamerasExt(maker, model, 0): cameras whose maker and
   model contain every word of the EXIF ones, best first."
  ([maker model] (find-cameras (database*) maker model))
  ([{:keys [cameras]} maker model]
   (let [mw (when (seq maker) (split-words maker))
         pw (when (seq model) (split-words model))]
     (score-sort
      (keep (fn [c]
              (let [s1 (if mw (ml-fuzzy mw (:maker c) true) 0)
                    s2 (if pw (ml-fuzzy pw (:model c) true) 0)]
                (when (and (or (nil? mw) (pos? s1)) (or (nil? pw) (pos? s2)))
                  [c (+ s1 s2)])))
            cameras)))))

(defn- compare-num [a b]
  (cond (or (nil? a) (nil? b) (zero? a) (zero? b)) 0
        :else (let [r (/ (double a) b)] (if (or (<= r 0.99) (>= r 1.01)) -1 1))))

(defn- crop-score [cam-crop calibs]
  (reduce (fn [best {c :crop}]
            (if (and (> cam-crop 0.01) (< cam-crop (* c 0.96)))
              best
              (max best (cond (>= cam-crop (* c 1.41)) 2 (>= cam-crop (* c 1.31)) 4
                              (>= cam-crop (* c 1.21)) 6 (>= cam-crop (* c 1.11)) 8
                              (>= cam-crop (* c 1.01)) 10 (>= cam-crop c) 5
                              (>= cam-crop (* c 0.96)) 3 :else 0))))
          0 calibs))

(defn match-score
  "lfDatabase::MatchScore of database lens `m` for the `pattern` lens (its
   model's words in :words) on `camera`; 0 when it can't be that lens."
  [{:keys [mounts]} pattern m camera]
  (let [compat (when-let [mt (:mount camera)] (not-empty (get mounts mt)))]
    (reduce
     (fn [score step] (let [s (step)] (if (nil? s) (reduced 0) (+ score s))))
     0
     [#(if (and camera (seq (:calibs m)))
         (let [cs (crop-score (double (:crop camera)) (:calibs m))] (when (pos? cs) cs))
         0)
      #(case (compare-num (first (:focal pattern)) (first (:focal m))) -1 nil 1 10 0)
      #(case (compare-num (second (:focal pattern)) (second (:focal m))) -1 nil 1 10 0)
      #(case (compare-num (first (:aperture pattern)) (first (:aperture m))) -1 nil 1 10 0)
      #(case (compare-num (second (:aperture pattern)) (second (:aperture m))) -1 nil 1 10 0)
      #(if (and (seq (:mounts m)) (or camera compat))
         (cond (and (:mount camera) (some (fn [x] (str= (:mount camera) x)) (:mounts m))) 10
               (some (fn [c] (some (fn [x] (str= c x)) (:mounts m))) compat) 9)
         0)
      #(if (and (seq (:maker pattern)) (seq (:maker m)))
         (when (mlstr= (first (:maker pattern)) (:maker m)) 10)
         0)
      #(if (and (:words pattern) (seq (:model m)))
         (let [s (ml-fuzzy (:words pattern) (:model m) true)]
           (when (pos? s) (max 1 (quot (* s 4) 10))))
         0)])))

(defn find-lenses
  "lfDatabase::FindLenses(camera, maker, model, 0): the database lenses that
   may be `model` (EXIF lens name) on `camera`, best first."
  ([camera maker model] (find-lenses (database*) camera maker model))
  ([{:keys [lenses] :as dbase} camera maker model]
   (let [model (not-empty model)
         pattern (-> (guess-parameters {:maker (when (seq maker) [maker]) :model (when model [model])
                                        :focal [0.0 0.0] :aperture [0.0 0.0]})
                     (assoc :words (when model (split-words model))))]
     (score-sort (keep #(let [s (match-score dbase pattern % camera)] (when (pos? s) [% s])) lenses)))))

(defn sanitize-lens
  "darktable's _lens_sanitize: an EXIF lens name up to \" or \" / \" (\"."
  [s]
  (when s
    (let [cut (keep #(let [i (str/index-of s %)] (when (and i (pos? i)) i)) [" or " " ("])]
      (if (seq cut) (subs s 0 (apply min cut)) s))))

(declare detect*)

(defn detect
  "The camera and lens of a photo from its EXIF {:make :model :lens}, the
   way darktable's lens module picks them: the best camera, then the best
   lens for it (a fixed-lens camera's own when the name finds none). Returns
   {:camera :lens :lenses (every candidate)}, nil parts when not found."
  ([exif] (detect* (select-keys exif [:make :model :lens])))
  ([dbase {:keys [make model lens]}]
   (let [cam (first (find-cameras dbase make model))
         name (sanitize-lens lens)
         fixed? (some-> (:mount cam) first Character/isLowerCase)
         ls (when cam (find-lenses dbase cam nil name))
         ls (if (and cam (empty? ls) fixed?) (find-lenses dbase cam nil nil) ls)]
     {:camera cam :lens (first ls) :lenses (vec ls)})))

(def ^:private detect* (memoize (fn [exif] (detect (database*) exif))))

(defn label [{:keys [maker model]}] (str/join " " (remove nil? [(first maker) (first model)])))

(defn lens-named
  "The database lens whose label is `s`."
  [s] (when s (first (filter #(= s (label %)) (:lenses (database*))))))

;; ---- calibration interpolation (lens.cpp) ---------------------------------------
(defn interpolate
  "_lf_interpolate: Hermite between y2 and y3 at t, with neighbours y1, y4
   (nil when missing)."
  [y1 y2 y3 y4 t]
  (let [t2 (* t t) t3 (* t2 t)
        tg2 (if (nil? y1) (- y3 y2) (* 0.5 (- y3 y1)))
        tg3 (if (nil? y4) (- y3 y2) (* 0.5 (- y4 y2)))]
    (+ (* (+ (* 2 t3) (* -3 t2) 1) y2)
       (* (+ t3 (* -2 t2) t) tg2)
       (* (+ (* -2 t3) (* 3 t2)) y3)
       (* (- t3 t2) tg3))))

(defn- calib-set
  "The calibration set with the closest crop factor not much bigger than
   `crop` that has `kind` data."
  [lens crop kind]
  (first (sort-by #(/ crop (:crop %))
                  (filter #(and (seq (get % kind)) (>= (/ crop (:crop %)) 0.96)) (:calibs lens)))))

(defn- spline-neighbours
  "__insert_spline over the entries: the two below `focal` (nearest last)
   and the two above (nearest first), as [s0 s1 s2 s3]."
  [entries focal]
  (let [below (sort-by #(- (nth % 1)) (filter #(< (nth % 1) focal) entries))
        above (sort-by #(nth % 1) (filter #(> (nth % 1) focal) entries))]
    [(second below) (first below) (first above) (second above)]))

(defn- param-scales
  "__parameter_scales for distortion (:dist) or TCA (:tca) term `i` of
   model at focal lengths fs (nil kept nil): the factor terms are
   multiplied by before interpolating and divided by after."
  [kind i fs]
  (if (and (= kind :tca) (< i 2)) (mapv #(when % 1.0) fs) fs))

(defn- first-model
  "The entries of the first model the calibration uses (lensfun ignores
   entries of other models), \"none\" skipped."
  [entries]
  (let [es (remove #(= "none" (first %)) entries)
        m (ffirst es)]
    (filterv #(= m (first %)) es)))

(defn- interpolate-focal
  "Interpolate distortion (:dist, entries [model focal real t...]) or TCA
   (:tca, [model focal t...]) at `focal` for a crop factor; {:model :terms
   :real-focal :crop :aspect} or nil."
  [lens crop focal kind]
  (let [key (if (= kind :dist) :distortion :tca)
        t0 (if (= kind :dist) 3 2)]
    (when-let [cs (calib-set lens crop key)]
      (let [es (first-model (get cs key))
            attr {:crop (:crop cs) :aspect (:aspect cs)}
            ->res (fn [e] (merge attr {:model (keyword (first e)) :focal (nth e 1)
                                       :real-focal (when (= kind :dist) (nth e 2))
                                       :terms (vec (drop t0 e))}))
            exact (first (filter #(== focal (nth % 1)) es))
            [s0 s1 s2 s3] (spline-neighbours es focal)]
        (cond
          exact (->res exact)
          (not (and s1 s2)) (some-> (or s1 s2) ->res)
          :else
          (let [t (/ (- focal (nth s1 1)) (- (nth s2 1) (nth s1 1)))
                fs (map #(some-> % (nth 1) double) [s0 s1 s2 s3])
                n (- (count s1) t0)]
            (merge attr
                   {:model (keyword (first s1)) :focal focal
                    :real-focal (when (= kind :dist)
                                  (interpolate (some-> s0 (nth 2)) (nth s1 2) (nth s2 2) (some-> s3 (nth 2)) t))
                    :terms (vec (for [i (range n)]
                                  (let [[v0 v1 v2 v3 vf] (param-scales kind i (concat fs [focal]))
                                        term #(* (nth %1 (+ t0 i)) %2)]
                                    (/ (interpolate (when s0 (term s0 v0)) (term s1 v1) (term s2 v2) (when s3 (term s3 v3)) t)
                                       vf))))})))))))

(defn interpolate-distortion "lfLens::InterpolateDistortion." [lens crop focal]
  (interpolate-focal lens crop focal :dist))

(defn interpolate-tca "lfLens::InterpolateTCA." [lens crop focal]
  (interpolate-focal lens crop focal :tca))

(defn- vignetting-dist
  "__vignetting_dist: how far calibration `e` is from (focal, aperture,
   distance), each axis made roughly linear and 0..1."
  [{[minf maxf] :focal} [_ ef ea ed] focal aperture distance]
  (let [df (- maxf minf)
        nf #(if (zero? df) (- % minf) (/ (- % minf) df))
        sq #(* % %)]
    (Math/sqrt (+ (sq (- (nf ef) (nf focal))) (sq (- (/ 4.0 ea) (/ 4.0 aperture)))
                  (sq (- (/ 0.1 ed) (/ 0.1 distance)))))))

(defn interpolate-vignetting
  "lfLens::InterpolateVignetting: inverse distance weighting (p = 3.5) over
   the calibrations; nil when none is close enough."
  [lens crop focal aperture distance]
  (when-let [cs (calib-set lens crop :vignetting)]
    (let [es (first-model (:vignetting cs))
          attr {:crop (:crop cs) :aspect (:aspect cs) :model (keyword (ffirst es))}
          ds (map #(vignetting-dist lens % focal aperture distance) es)
          exact (first (filter #(< (second %) 1e-4) (map vector es ds)))]
      (cond
        exact (assoc attr :terms (vec (drop 4 (first exact))))
        (> (apply min ds) 1.0) nil
        :else (let [ws (map #(Math/abs (/ 1.0 (Math/pow % 3.5))) ds)
                    total (reduce + ws)]
                (assoc attr :terms (vec (for [i (range 3)]
                                          (/ (reduce + (map #(* %1 (nth %2 (+ 4 i))) ws es)) total)))))))))

;; ---- the modifier (modifier.cpp, mod-*.cpp) --------------------------------------
(def ^:private full-frame-diag (Math/hypot 36.0 24.0))

(defn- hugin-scaling
  "real focal / the calibration's hugin unit (half its sensor's short side;
   for vignetting its half diagonal) in mm."
  [real-focal {:keys [crop aspect]} vignetting?]
  (/ real-focal (/ full-frame-diag crop (if vignetting? 1.0 (Math/hypot aspect 1.0)) 2.0)))

(defn rescale-distortion
  "mod-coord.cpp rescale_polynomial_coefficients: the terms in units of
   the real focal length."
  [{:keys [model terms] :as lcd} real-focal]
  (let [h (hugin-scaling real-focal lcd false)
        [a b c] terms]
    (assoc lcd :terms
           (case model
             :poly3 (let [d (- 1 a)] (assoc terms 0 (/ (* a (Math/pow h 2)) (Math/pow d 3))))
             :poly5 (assoc terms 0 (* a (Math/pow h 2)) 1 (* b (Math/pow h 4)))
             :ptlens (let [d (- 1 a b c)]
                       (assoc terms 0 (/ (* a (Math/pow h 3)) (Math/pow d 4))
                              1 (/ (* b (Math/pow h 2)) (Math/pow d 3))
                              2 (/ (* c h) (Math/pow d 2))))
             terms))))

(defn rescale-tca
  "mod-subpix.cpp rescale_polynomial_coefficients (forward)."
  [{:keys [model terms] :as lct} real-focal]
  (let [h (hugin-scaling real-focal lct false)]
    (assoc lct :terms (if (= :poly3 model)
                        (-> terms (update 2 * h) (update 3 * h) (update 4 * h h) (update 5 * h h))
                        terms))))

(defn rescale-vignetting
  "mod-color.cpp rescale_polynomial_coefficients."
  [{:keys [model terms] :as lcv} real-focal]
  (let [h (hugin-scaling real-focal lcv true)]
    (assoc lcv :terms (if (= :pa model)
                        (mapv * terms [(Math/pow h 2) (Math/pow h 4) (Math/pow h 6)])
                        terms))))

(defn distort
  "ModifyCoord_Dist_*: a corrected (undistorted) normalized point [x y] ->
   where it lies in the distorted photo."
  [{:keys [model terms]} [x y]]
  (let [ru2 (+ (* x x) (* y y))
        f (case model
            :poly3 (+ 1 (* (nth terms 0) ru2))
            :poly5 (+ 1 (* (nth terms 0) ru2) (* (nth terms 1) ru2 ru2))
            :ptlens (let [r (Math/sqrt ru2) [a b c] terms] (+ 1 (* a ru2 r) (* b ru2) (* c r)))
            1.0)]
    [(* x f) (* y f)]))

(defn tca-factors
  "ModifyCoord_TCA_*: at normalized distance r, [red blue] factors
   (green stays)."
  [{:keys [model terms]} r]
  (case model
    :linear [(nth terms 0) (nth terms 1)]
    :poly3 (let [[vr vb cr cb br bb] terms r2 (* r r)]
             [(+ (* br r2) (* cr r) vr) (+ (* bb r2) (* cb r) vb)])
    [1.0 1.0]))

(defn modifier
  "lfModifier for a w x h photo: its normalization (NormScale: pixels to
   units of the real focal length) and centre (pixels), and the corrections
   it does, rescaled to those units. `corr` {:distortion :tca :vignetting},
   each an interpolation (or nil)."
  [{:keys [lens crop focal w h]} {:keys [distortion tca vignetting]}]
  (let [width (double (if (>= w 2) (dec w) 1)) height (double (if (>= h 2) (dec h) 1))
        real-focal (double (or (:real-focal distortion) focal))
        ns (/ full-frame-diag crop (Math/hypot (inc width) (inc height)) real-focal)
        size (min width height)
        [lcx lcy] (or (:center lens) [0.0 0.0])]
    {:norm-scale ns :width width :height height
     :center [(+ (/ width 2.0) (* (/ size 2.0) lcx)) (+ (/ height 2.0) (* (/ size 2.0) lcy))]
     :distortion (some-> distortion (rescale-distortion real-focal))
     :tca (some-> tca (rescale-tca real-focal))
     :vignetting (some-> vignetting (rescale-vignetting real-focal))}))

(defn- residual
  "AutoscaleResidualDistance: how far outside the photo (normalized) a point is."
  [{:keys [width height norm-scale]} [x y]]
  (let [mx (* 0.5 width norm-scale) my (* 0.5 height norm-scale)]
    (max (- x mx) (- (- mx) x) (- y my) (- (- my) y))))

(defn- transformed-distance
  "GetTransformedDistance: the corrected radius along `angle` that distorts
   onto the photo's edge (Newton), or -1 when it doesn't converge.

   Unlike lensfun, which stops within 0.001 (100 x NEWTON_EPS) in units of
   the focal length, this stops within 0.01 px: at long focal lengths the
   whole half diagonal is only a few hundredths of a unit, so lensfun's
   tolerance is percent of the frame and leaves the corners unfilled (a 480
   mm lens: 2%)."
  [{:keys [distortion norm-scale] :as m} dist angle]
  (let [sa (Math/sin angle) ca (Math/cos angle)
        tol (* 0.01 norm-scale)
        f (fn [ru] (residual m (distort distortion [(* ca ru) (* sa ru)])))]
    (loop [ru dist dx (* 0.1 norm-scale) countdown 50]
      (let [rd (f ru)]
        (cond
          (< (- tol) rd tol) ru
          (zero? countdown) -1.0
          :else (let [rd1 (f (+ ru dx))]
                  (if (< (Math/abs (- rd1 rd)) (* 1e-4 tol))
                    (recur ru (* 2 dx) (dec countdown))
                    (recur (- ru (/ rd (/ (- rd1 rd) dx))) dx (dec countdown)))))))))

(defn auto-scale
  "GetAutoScale: how much to enlarge the corrected image so no edge of it
   falls outside the photo (lensfun checks the corners and edge middles)."
  [{:keys [width height norm-scale distortion tca]}]
  (let [sub (if tca 1.001 1.0)]
    (if-not distortion
      sub
      (let [a1 (Math/atan2 height width)
            diag (* 0.5 (Math/hypot width height) norm-scale)
            pts [[0.0 (* width 0.5 norm-scale)] [a1 diag] [(/ Math/PI 2) (* height 0.5 norm-scale)]
                 [(- Math/PI a1) diag] [Math/PI (* width 0.5 norm-scale)] [(+ Math/PI a1) diag]
                 [(* 1.5 Math/PI) (* height 0.5 norm-scale)] [(- (* 2 Math/PI) a1) diag]]
            m {:width width :height height :norm-scale norm-scale :distortion distortion}]
        (* sub 1.001
           (reduce (fn [s [angle dist]] (max s (/ dist (transformed-distance m dist angle)))) 0.01 pts))))))

;; ---- plans: radial tables ------------------------------------------------------
(def table-size "Entries in each radial table." 1024)

(defn- table [n rmax f] (vec (for [i (range n)] (double (f (* rmax (/ i (double (dec n)))))))))

(defn lensfun-plan
  "The correction of a w x h photo by lensfun calibrations: {:center [x y]
   :rmax :k [kr kg kb] :vrmax :vig}. k tables: source offset / corrected
   offset at corrected radius r (0..rmax px); vig: the gain at source radius
   (0..vrmax px), or nil. `scale` enlarges the result (auto-scale's, or 1)."
  [{:keys [w h] :as photo} corr scale]
  (let [{:keys [norm-scale center distortion tca vignetting]} (modifier photo corr)
        inv (/ 1.0 scale)
        rmax (+ 2.0 (* 0.5 (Math/hypot w h)))
        ratio (fn [ch r]
                (let [u (max (* r norm-scale) 1e-9)
                      [x _] (distort distortion [(* u inv) 0.0])
                      [fr fb] (if tca (tca-factors tca (Math/abs x)) [1.0 1.0])]
                  (/ (* x (case ch 0 fr 1 1.0 2 fb)) u)))
        vrmax (* 1.25 rmax)
        vig (when-let [[k1 k2 k3] (:terms vignetting)]
              (table table-size vrmax
                     (fn [r] (let [v2 (Math/pow (* r norm-scale) 2)]
                               (/ 1.0 (+ 1 (* k1 v2) (* k2 v2 v2) (* k3 v2 v2 v2)))))))]
    {:center center :rmax rmax :vrmax vrmax
     :k (mapv #(table table-size rmax (partial ratio %)) [0 1 2])
     :vig vig}))

;; ---- embedded corrections (darktable lens.cc, \"embedded metadata\") -------------
(defn- linear-spline
  "_interpolate_linear_spline."
  [xs ys x]
  (let [n (count xs)]
    (cond
      (< x (first xs)) (first ys)
      :else (or (some (fn [i] (let [x0 (nth xs (dec i)) x1 (nth xs i)]
                                (when (<= x0 x x1)
                                  (+ (nth ys (dec i)) (* (- x x0) (/ (- (nth ys i) (nth ys (dec i))) (- x1 x0)))))))
                      (range 1 n))
                (nth ys (dec n))))))

(defn sony-coeffs
  "_init_coeffs_md_v1 for Sony's DistortionCorrParams, Chromatic-
   AberrationCorrParams and VignettingCorrParams (each count-prefixed as
   in the file), before scaling: {:knots :cor [r g b] :vig}."
  [{:keys [distortion ca vignetting]} {:keys [dist? tca? vig?]}]
  (let [nc (first distortion)
        d (vec (take nc (rest distortion)))
        car (vec (take nc (rest ca))) cab (vec (take nc (drop (inc nc) ca)))
        v (vec (take nc (rest vignetting)))
        base (mapv #(if dist? (+ 1 (* % (Math/pow 2 -14))) 1.0) d)]
    {:knots (mapv #(/ (+ % 0.5) (dec nc)) (range nc))
     :cor [(if tca? (mapv #(* %1 (+ 1 (* %2 (Math/pow 2 -21)))) base car) base)
           base
           (if tca? (mapv #(* %1 (+ 1 (* %2 (Math/pow 2 -21)))) base cab) base)]
     :vig (mapv #(if vig? (Math/pow (Math/pow 2 (- 0.5 (Math/pow 2 (- (* % (Math/pow 2 -13)) 1)))) 2) 1.0) v)}))

(defn sony-valid?
  "darktable's check: at most 16 knots, matching counts."
  [{:keys [distortion ca vignetting]}]
  (let [nc (first distortion)]
    (boolean (and nc (pos? nc) (<= nc 16) (= (* 2 nc) (first ca)) (= nc (first vignetting))
                  (>= (count distortion) (inc nc)) (>= (count ca) (inc (* 2 nc))) (>= (count vignetting) (inc nc))))))

(defn md-auto-scale
  "_get_autoscale_md_v1: the largest correction over radii 0.5..1."
  [{:keys [knots cor]}]
  (reduce max 0.0 (for [i (range 200) c cor] (linear-spline knots c (+ 0.5 (* 0.5 (/ i 199.0)))))))

(defn embedded-plan
  "The correction of a w x h photo from its embedded coefficients
   (sony-coeffs) as radial tables (lensfun-plan's shape). Radii are
   relative to the half diagonal; `autofill?` scales away the borders."
  [{:keys [w h]} coeffs autofill?]
  (let [{:keys [knots cor vig]} coeffs
        scale (if autofill? (/ 1.0 (md-auto-scale coeffs)) 1.0)
        w2 (* 0.5 w) h2 (* 0.5 h)
        rnorm (Math/hypot w2 h2)
        rmax (+ 2.0 rnorm)
        vrmax (* 1.25 rmax)]
    {:center [w2 h2] :rmax rmax :vrmax vrmax
     :k (mapv (fn [c] (table table-size rmax #(* scale (linear-spline knots c (/ % rnorm))))) cor)
     :vig (when (some #(not= 1.0 %) vig)
            (table table-size vrmax #(/ 1.0 (max 1e-4 (linear-spline knots vig (/ % rnorm))))))}))

;; ---- choosing ------------------------------------------------------------------
(defn crop-factor
  "The camera's crop factor: the database's, else from EXIF focal lengths."
  [camera {:keys [focal focal35]}]
  (or (when (and camera (pos? (:crop camera))) (:crop camera))
      (when (and focal focal35 (pos? focal) (pos? focal35)) (/ (double focal35) focal))
      1.0))

(defn plan
  "How to correct a w x h photo: {:method :lensfun|:embedded ...plan}, or
   nil when there is nothing to correct with. `photo` {:w :h :exif
   :embedded (raw correction data or nil)}; `opts` {:method (:auto,
   :lensfun or :embedded) :lens (a database lens to use instead of the
   detected one) :dist? :tca? :vig? :autofill?}. Lensfun vignetting is
   for the shot's aperture, at a distance of 1000 m (darktable's default
   when EXIF has none)."
  [{:keys [w h exif embedded]} {:keys [method lens dist? tca? vig? autofill?] :as opts}]
  (let [use-md? (and embedded (sony-valid? embedded) (#{:auto :embedded} (or method :auto)))]
    (if use-md?
      (assoc (embedded-plan {:w w :h h} (sony-coeffs embedded opts) autofill?) :method :embedded)
      (when-not (= :embedded method)
        (let [{:keys [camera] detected :lens} (detect exif)
              lens (or (if (string? lens) (lens-named lens) lens) detected)
              focal (:focal exif)]
          (when (and lens focal (pos? focal))
            (let [crop (crop-factor camera exif)
                  aperture (:fnumber exif)
                  corr {:distortion (when dist? (interpolate-distortion lens crop focal))
                        :tca (when tca? (interpolate-tca lens crop focal))
                        :vignetting (when (and vig? aperture (pos? aperture))
                                      (interpolate-vignetting lens crop focal aperture 1000.0))}
                  photo {:lens lens :crop crop :focal focal :w w :h h}
                  scale (if autofill? (auto-scale (modifier photo corr)) 1.0)]
              (when (some some? (vals corr))
                (assoc (lensfun-plan photo corr scale)
                       :method :lensfun :lens lens :camera camera :scale scale
                       :corrections (set (keep (fn [[k v]] (when v k)) corr)))))))))))
