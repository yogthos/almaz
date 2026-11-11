(ns almaz.strip
  "Filmstrip geometry. Pure. Thumbnails sit in fixed-width slots along one
   row, `pad` in from the ends and `gap` apart; the row is scrolled sideways
   by an offset in pixels. `g` is {:pad :slot :gap}.")

(defn content-width
  "The row's full width for `n` thumbnails."
  [n {:keys [pad slot gap]}]
  (+ (* 2 pad) (* n slot) (* (max 0 (dec n)) gap)))

(defn clamp-offset
  "`off` kept between the row's start and its end lined up with the view's
   right edge (0 when everything fits)."
  [off n view-w g]
  (double (max 0 (min off (- (content-width n g) view-w)))))

(defn slot-x
  "Slot `i`'s left edge in view coordinates."
  [i off {:keys [pad slot gap]}]
  (double (- (+ pad (* i (+ slot gap))) off)))

(defn index-at
  "The slot under view x `x`, or nil (padding, gaps, past the end)."
  [x off n {:keys [pad slot gap]}]
  (let [cx (- (+ x off) pad)
        step (+ slot gap)
        i (long (Math/floor (/ cx step)))]
    (when (and (>= cx 0) (< i n) (< (- cx (* i step)) slot))
      i)))

(defn reveal
  "The offset nearest `off` that shows slot `i` whole."
  [off i n view-w {:keys [pad slot gap] :as g}]
  (let [left (+ pad (* i (+ slot gap)))
        off (cond (< left off) (- left pad)
                  (> (+ left slot) (+ off view-w)) (- (+ left slot pad) view-w)
                  :else off)]
    (clamp-offset off n view-w g)))
