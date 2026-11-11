(ns almaz.autopilot
  "Autopilot: turn image estimates into a filter stack. Pure.

   The estimates come from almaz.pixels/estimate: `noise` is the luminance
   noise sigma (0..1 units) of the flattest parts of the image, `sharpness`
   the edge gradient energy relative to contrast. The thresholds are
   calibrated on camera files: ~0.002 is a clean base-ISO image, ~0.03 a
   high-ISO one; sharp focus scores above ~0.3.")

(def ^:private noise-floor 0.006)
(def ^:private soft-below 0.3)

(defn- clamp [lo hi v] (min hi (max lo v)))

(defn noisy?
  "Noisy enough to need denoising (estimates may be nil before analysis)."
  [estimate]
  (> (or (:noise estimate) 0.0) noise-floor))

(def ^:private max-strength
  "Autopilot's ceiling: past this the AI filters look processed (waxy skin,
   haloed edges); anyone who wants more can drag the slider."
  0.6)

(defn suggest
  "The layers to add, in pipeline order: [{:kind :opacity} ...]."
  [{:keys [noise sharpness]}]
  (cond-> []
    (> noise noise-floor)
    (conj {:kind :denoise
           :opacity (clamp 0.2 max-strength (+ 0.25 (* 15.0 (- noise noise-floor))))})

    (< sharpness soft-below)
    (conj {:kind :sharpen
           :opacity (clamp 0.15 max-strength (+ 0.15 (* 0.45 (/ (- soft-below sharpness) soft-below))))})))

(defn describe [{:keys [noise sharpness]}]
  (format "noise %.1f%%  sharpness %.2f" (* 100.0 noise) (double sharpness)))

;; ---- exposure ------------------------------------------------------------------
(def ^:private clip-fraction "The share of pixels auto exposure lets reach white." 0.01)
(def ^:private max-ev 5.0)

(defn- srgb->linear [v]
  (if (<= v 0.04045) (/ v 12.92) (Math/pow (/ (+ v 0.055) 1.055) 2.4)))

(defn auto-exposure
  "The exposure (EV) that lifts an image until its brightest 1% reaches
   white, from its 256-bin luma histogram: what raw converters do by
   default, offered on request. Never darkens; at most +5 EV."
  [luma]
  (let [total (reduce + luma)]
    (if (zero? total)
      0.0
      ;; the bin where the brightest 1% begins
      (let [top (loop [i 255 acc 0]
                  (let [acc (+ acc (nth luma i))]
                    (if (or (zero? i) (>= acc (* clip-fraction total))) i (recur (dec i) acc))))
            lin (srgb->linear (/ (+ top 0.5) 256.0))]
        (if (>= top 254)
          0.0
          (/ (Math/round (* 100.0 (min max-ev (- (/ (Math/log lin) (Math/log 2.0)))))) 100.0))))))
