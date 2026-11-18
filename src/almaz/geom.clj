(ns almaz.geom
  "Orientation and crop: which part of the source image is shown and
   exported, and which way up. Pure.

   A document's pixels are never moved. Its geometry is
     :orient  0..7, quarter turns clockwise (low two bits) after an optional
              horizontal mirror (bit 2), the way darktable's flip module
              keeps it as a few bits instead of resampling
     :crop-x :crop-y :crop-w :crop-h  the kept rectangle in source pixels
              (nil: the whole image)
   Display coordinates are the oriented crop (or, while the crop is being
   edited, the whole oriented image). Every pixel path (render, histogram,
   export) gets the display -> source mapping as an affine transform.

   An affine transform is [a b c d e f]: x' = a x + b y + c,
   y' = d x + e y + f. A rect is [x y w h].")

;; ---- affine transforms ----------------------------------------------------------
(def identity-xf [1.0 0.0 0.0 0.0 1.0 0.0])

(defn apply-xf [[a b c d e f] [x y]]
  [(+ (* a x) (* b y) c) (+ (* d x) (* e y) f)])

(defn compose
  "f after g."
  [[a b c d e f] [A B C D E F]]
  [(+ (* a A) (* b D)) (+ (* a B) (* b E)) (+ (* a C) (* b F) c)
   (+ (* d A) (* e D)) (+ (* d B) (* e E)) (+ (* d C) (* e F) f)])

(defn invert [[a b c d e f]]
  (let [det (double (- (* a e) (* b d)))]
    [(/ e det) (/ (- b) det) (/ (- (* b f) (* c e)) det)
     (/ (- d) det) (/ a det) (/ (- (* c d) (* a f)) det)]))

(defn translate [dx dy] [1.0 0.0 (double dx) 0.0 1.0 (double dy)])

(defn xf-rect
  "The bounding rect of `rect` mapped through `xf`."
  [xf [x y w h]]
  (let [ps (map #(apply-xf xf %) [[x y] [(+ x w) y] [x (+ y h)] [(+ x w) (+ y h)]])
        xs (map first ps) ys (map second ps)
        x0 (apply min xs) y0 (apply min ys)]
    [x0 y0 (- (apply max xs) x0) (- (apply max ys) y0)]))

;; ---- orientation ----------------------------------------------------------------
(defn- turns [o] (bit-and (long o) 3))
(defn- mirrored? [o] (bit-test (long o) 2))
(defn- orient [turns mirror?] (+ (mod turns 4) (if mirror? 4 0)))

(defn rotate
  "Turn the displayed image a quarter clockwise (dir 1) or anticlockwise (-1)."
  [o dir] (orient (+ (turns o) dir) (mirrored? o)))

(defn flip-h
  "Mirror the displayed image left to right. A mirror reverses the turns
   before it: H R^r = R^-r H."
  [o] (orient (- (turns o)) (not (mirrored? o))))

(defn flip-v
  "Mirror the displayed image top to bottom (a half turn after a mirror)."
  [o] (orient (- 2 (turns o)) (not (mirrored? o))))

(defn oriented-size
  "The size of a w x h image shown with orientation `o`."
  [o w h] (if (odd? (turns o)) [h w] [w h]))

(defn orient-xf
  "Source (w x h) -> oriented coordinates: mirror, then the quarter turns."
  [o w h]
  (loop [xf (if (mirrored? o) [-1.0 0.0 (double w) 0.0 1.0 0.0] identity-xf)
         n (turns o) [cw ch] [w h]]
    (if (zero? n)
      xf
      ;; a clockwise quarter turn of a cw x ch image: (x, y) -> (ch - y, x)
      (recur (compose [0.0 -1.0 (double ch) 1.0 0.0 0.0] xf) (dec n) [ch cw]))))

;; ---- crop -----------------------------------------------------------------------
(def min-crop "The smallest crop side, source px." 16)

(defn cropped? [{:keys [crop-w]}] (some? crop-w))

(defn crop-rect
  "The kept rectangle in source pixels."
  [{:keys [w h crop-x crop-y crop-w crop-h] :as doc}]
  (if (cropped? doc) [crop-x crop-y crop-w crop-h] [0 0 w h]))

(defn- shown-rect
  "The source rect the display covers: the crop, or all of it while editing."
  [doc editing?]
  (if editing? [0 0 (:w doc) (:h doc)] (crop-rect doc)))

(defn display-size
  "[w h] of what is shown (and exported, when not `editing?` the crop)."
  [doc editing?]
  (let [[_ _ w h] (shown-rect doc editing?)]
    (oriented-size (or (:orient doc) 0) w h)))

(defn display->source
  "The display -> source pixel mapping."
  [doc editing?]
  (let [[x y w h] (shown-rect doc editing?)]
    (compose (translate x y) (invert (orient-xf (or (:orient doc) 0) w h)))))

(defn display-crop
  "The crop as the crop editor shows it: in oriented whole-image coordinates."
  [doc]
  (xf-rect (orient-xf (or (:orient doc) 0) (:w doc) (:h doc)) (crop-rect doc)))

(defn with-display-crop
  "Set the doc's crop from a rect in oriented whole-image coordinates
   (display-crop's), rounded to whole source pixels."
  [{:keys [w h] :as doc} rect]
  (let [[x y cw ch] (xf-rect (invert (orient-xf (or (:orient doc) 0) w h)) rect)
        x0 (max 0 (Math/round (double x))) y0 (max 0 (Math/round (double y)))
        x1 (min w (Math/round (double (+ x cw)))) y1 (min h (Math/round (double (+ y ch))))]
    (if (and (= [x0 y0] [0 0]) (= [x1 y1] [w h]))
      (assoc doc :crop-x nil :crop-y nil :crop-w nil :crop-h nil)
      (assoc doc :crop-x x0 :crop-y y0 :crop-w (- x1 x0) :crop-h (- y1 y0)))))

(defn- clamp [v lo hi] (max lo (min hi v)))

(defn- place
  "A w x h rect centred on (cx, cy), shifted to lie inside bounds."
  [cx cy w h [bw bh]]
  [(clamp (- cx (/ w 2.0)) 0.0 (- bw w)) (clamp (- cy (/ h 2.0)) 0.0 (- bh h)) w h])

(defn fit-aspect
  "The largest rect of aspect `a` (w/h) inside bounds, centred on `rect`."
  [[x y w h] a [bw bh]]
  (let [[nw nh] (if (> (/ bw (double bh)) a) [(* bh a) bh] [bw (/ bw a)])]
    (place (+ x (/ w 2.0)) (+ y (/ h 2.0)) (double nw) (double nh) [bw bh])))

(defn fit-size
  "A [sw sh] rect centred on `rect`, scaled down when bounds can't hold it."
  [[x y w h] [sw sh] [bw bh]]
  (let [k (min 1.0 (/ bw (double sw)) (/ bh (double sh)))]
    (place (+ x (/ w 2.0)) (+ y (/ h 2.0)) (* k sw) (* k sh) [bw bh])))

(def ^:private moves
  "Which edges a handle moves: [left top right bottom]."
  {:nw [1 1 0 0] :n [0 1 0 0] :ne [0 1 1 0] :e [0 0 1 0]
   :se [0 0 1 1] :s [0 0 0 1] :sw [1 0 0 1] :w [1 0 0 0]})

(defn- drag-free [[x y w h] handle [dx dy] [bw bh]]
  (let [[ml mt mr mb] (moves handle)
        l (if (= 1 ml) (clamp (+ x dx) 0.0 (- (+ x w) min-crop)) x)
        t (if (= 1 mt) (clamp (+ y dy) 0.0 (- (+ y h) min-crop)) y)
        r (if (= 1 mr) (clamp (+ x w dx) (+ l min-crop) bw) (+ x w))
        b (if (= 1 mb) (clamp (+ y h dy) (+ t min-crop) bh) (+ y h))]
    [l t (- r l) (- b t)]))

;; One axis of a locked-aspect drag, by which side moves: s = 1 the far
;; side (right/bottom), -1 the near one, 0 neither (it grows about the centre).
(defn- side-sign [near far] (cond (= 1 far) 1 (= 1 near) -1 :else 0))

(defn- anchor
  "The axis's fixed point: the side that stays, or the centre."
  [s start len]
  (case s 1 start -1 (+ start len) (+ start (/ len 2.0))))

(defn- room
  "How long the axis can get about anchor `a` within [0, bound]."
  [s a bound]
  (case s 1 (- bound a) -1 a (* 2 (min a (- bound a)))))

(defn- start-from
  "Where the axis starts, `len` long about anchor `a`."
  [s a len]
  (case s 1 a -1 (- a len) (- a (/ len 2.0))))

(defn- locked-size
  "The dragged size [w h] at aspect `a`: an edge sets its own dimension, a
   corner whichever gives the larger crop."
  [[sx sy] [w1 h1] a]
  (cond (zero? sy) [w1 (/ w1 a)]
        (zero? sx) [(* h1 a) h1]
        (>= (/ w1 a) h1) [w1 (/ w1 a)]
        :else [(* h1 a) h1]))

(defn- drag-locked
  "Resize keeping aspect `a`: corners pivot on the opposite corner, edges on
   the opposite edge's centre; the result is shrunk to stay inside bounds."
  [[x y w h] handle [dx dy] [bw bh] a]
  (let [[ml mt mr mb] (moves handle)
        sx (side-sign ml mr) sy (side-sign mt mb)
        [w1 h1] (locked-size [sx sy] [(max min-crop (+ w (* sx dx))) (max min-crop (+ h (* sy dy)))] a)
        ax (anchor sx x w) ay (anchor sy y h)
        k (min 1.0 (/ (room sx ax bw) w1) (/ (room sy ay bh) h1))
        w1 (* k w1) h1 (* k h1)]
    [(start-from sx ax w1) (start-from sy ay h1) w1 h1]))

(defn drag-crop
  "The crop `rect` after dragging `handle` (:move, :n, :ne, ... :nw) by
   [dx dy], inside bounds [w h], keeping aspect `a` (w/h) when not nil."
  [[x y w h :as rect] handle [dx dy] [bw bh :as bounds] a]
  (cond
    (= :move handle) [(clamp (+ x dx) 0.0 (- bw w)) (clamp (+ y dy) 0.0 (- bh h)) w h]
    a (drag-locked rect handle [dx dy] bounds a)
    :else (drag-free rect handle [dx dy] bounds)))

(defn- near-edges
  "Which of the rect's edges p is within `tol` of (:l :r :t :b), and whether
   it's within the rect's span on each axis (:in-x :in-y)."
  [[x y w h] [px py] tol]
  (let [near (fn [a b] (<= (Math/abs (- (double a) (double b))) tol))
        within (fn [v lo hi] (<= (- lo tol) v (+ hi tol)))]
    (cond-> #{}
      (near px x) (conj :l) (near px (+ x w)) (conj :r)
      (near py y) (conj :t) (near py (+ y h)) (conj :b)
      (within px x (+ x w)) (conj :in-x) (within py y (+ y h)) (conj :in-y))))

(def ^:private handle-rules
  "Corners first, then edges: [edges needed, handle]."
  [[[:t :l] :nw] [[:t :r] :ne] [[:b :l] :sw] [[:b :r] :se]
   [[:t :in-x] :n] [[:b :in-x] :s] [[:l :in-y] :w] [[:r :in-y] :e]])

(defn handle-at
  "The crop handle at point p (display coords) within `tol`: a corner, an
   edge, :move inside, or nil outside."
  [[x y w h :as rect] [px py] tol]
  (let [flags (near-edges rect [px py] tol)]
    (or (some (fn [[need handle]] (when (every? flags need) handle)) handle-rules)
        (when (and (< x px (+ x w)) (< y py (+ y h))) :move))))

;; ---- constraints ----------------------------------------------------------------
(def constraints
  "The crop menu: {:key :label}, with :aspect [w h] (display) or :size [w h]
   (display px). :original keeps the image's shape; :custom-ratio the doc's
   :ratio-w : :ratio-h; :custom-size takes a typed size and resizes freely."
  (into [{:key :free :label "Free"}
         {:key :custom-size :label "Custom Size"}
         {:key :custom-ratio :label "Custom Aspect Ratio"}
         {:key :original :label "Original"}]
        (concat
         (for [[w h] [[16 9] [5 3] [3 2] [7 5] [4 3] [5 4] [1 1] [4 5] [3 4] [5 7] [2 3] [3 5] [9 16]]]
           (if (= w h)
             {:key :square :label "Square" :aspect [1 1]}
             {:key (keyword (str "r" w "-" h)) :label (str w ":" h) :aspect [w h]}))
         (for [[w h] [[1920 1080] [1024 768]]]
           {:key (keyword (str "s" w "x" h)) :label (str w " × " h " px") :size [w h]}))))

(def ^:private constraint-by-key (into {} (map (juxt :key identity)) constraints))

(defn constraint-aspect
  "The aspect (display w/h) the doc's crop keeps, or nil when free."
  [{:keys [constrain ratio-w ratio-h w h] :as doc}]
  (let [{:keys [aspect size]} (constraint-by-key constrain)
        [a b] (or aspect size)]
    (cond
      (= :original constrain) (let [[ow oh] (oriented-size (or (:orient doc) 0) w h)] (/ ow (double oh)))
      (= :custom-ratio constrain) (/ (double ratio-w) ratio-h)
      a (/ (double a) b))))

(defn- bounds [doc] (oriented-size (or (:orient doc) 0) (:w doc) (:h doc)))

(defn constrain
  "Switch the doc's crop constraint to `key`, fitting the crop to it."
  [doc key]
  (let [doc (assoc doc :constrain key)
        {:keys [size]} (constraint-by-key key)
        a (constraint-aspect doc)
        r (display-crop doc)]
    (cond
      size (with-display-crop doc (fit-size r size (bounds doc)))
      a (with-display-crop doc (fit-aspect r a (bounds doc)))
      :else doc)))

(defn with-crop-size
  "An exact display size [w h] for the crop, centred on it and shrunk to fit."
  [doc [w h]]
  (let [[bw bh] (bounds doc)]
    (with-display-crop doc (fit-size (display-crop doc) [(min w bw) (min h bh)] [bw bh]))))
