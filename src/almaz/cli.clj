(ns almaz.cli
  "The command line: parsing it and planning what to do with each image.
   Pure; almaz.batch runs the plan.

   Filters take key=value settings after their flag (`--noise strength=40`),
   the way the reference app's automation CLI does. Without --override,
   autopilot chooses the filters from each image's estimate and the command
   line adjusts them (or turns one off with enabled=false); with --override,
   exactly the filters given run."
  (:require [clojure.string :as str]
            [almaz.autopilot :as ap]
            [almaz.doc :as d]))

(def usage
  "Usage: almaz [options] FILE...

With no options, opens FILEs in the app. With any option, processes them
without a window and writes the results.

Filters (settings follow the flag as key=value):
  --noise   [strength=0..100] [detail=0..100] [enabled=false]
  --sharpen [strength=0..100] [model=ai|classic] [radius=0.5..8]
            [amount=0..300] [enabled=false]
  --light   [exposure=-5..5|auto] [contrast=-100..100] [highlights=..]
            [shadows=..] [whites=..] [blacks=..] [saturation=..]
            [strength=0..100] [enabled=false]
            light adjustments over the result (exposure in EV; auto lifts
            the image until its brightest 1% reaches white); none unless
            asked for: RAW files keep their recorded exposure
  --override        run exactly the filters given (autopilot off); otherwise
                    autopilot picks filters per image and these adjust them
  --showSettings    print each image's estimate and filters before processing

Output:
  -o, --output DIR  where to write (default: next to each input)
  --format F        jpeg (default), tiff (16-bit), png (16-bit), png8
  --quality N       JPEG quality, 1..100 (default 95)
  -h, --help        this text

Results are named <input>-almaz.<ext> and keep the input's EXIF.")

(def ^:private filter-flags {"--noise" :noise "--sharpen" :sharpen "--light" :light})

(def ^:private tone-range [-100.0 100.0])

(def ^:private filter-keys
  {:noise   {"strength" :number "detail" :number "enabled" :bool}
   :sharpen {"strength" :number "model" #{"ai" "classic"} "radius" :number
             "amount" :number "enabled" :bool}
   :light   {"exposure" :exposure "contrast" tone-range "highlights" tone-range
             "shadows" tone-range "whites" tone-range "blacks" tone-range
             "saturation" tone-range "strength" :number "enabled" :bool}})

(def ^:private formats {"jpeg" :jpeg "jpg" :jpeg "tiff" :tiff16 "tif" :tiff16
                        "png" :png16 "png8" :png8})

(def ext {:jpeg "jpg" :tiff16 "tif" :png16 "png" :png8 "png"})

(defn- finder-arg?
  "The -psn_… process serial number older macOS passes a Finder-launched app."
  [a]
  (str/starts-with? a "-psn_"))

(defn batch?
  "Do these arguments ask for batch processing (any option at all)?"
  [args]
  (boolean (some #(and (str/starts-with? % "-") (not (finder-arg? %))) args)))

(defn- parse-number [s]
  (try (Double/parseDouble s) (catch Exception _ nil)))

(defn- number-setting [k v]
  (if-let [n (parse-number v)] [(keyword k) n] {:error (str k " must be a number, got " v)}))

(defn- bool-setting [k v]
  (if (#{"true" "false"} v) [(keyword k) (= v "true")] {:error (str k " must be true or false, got " v)}))

(defn- exposure-setting [_ v]
  (let [n (parse-number v)]
    (cond (= v "auto") [:exposure :auto]
          (and n (<= -5.0 n 5.0)) [:exposure n]
          :else {:error (str "exposure must be -5..5 or auto, got " v)})))

(defn- range-setting [[lo hi]]
  (fn [k v]
    (let [n (parse-number v)]
      (if (and n (<= lo n hi))
        [(keyword k) n]
        {:error (str k " must be " (long lo) ".." (long hi) ", got " v)}))))

(defn- choice-setting [choices]
  (fn [k v]
    (if (choices v)
      [(keyword k) (keyword v)]
      {:error (str k " must be one of " (str/join ", " (sort choices)) ", got " v)})))

(defn- setting-parser
  "How a filter key's value parses (filter-keys' kinds)."
  [kind]
  (cond (= kind :number) number-setting
        (= kind :bool) bool-setting
        (= kind :exposure) exposure-setting
        (vector? kind) (range-setting kind)
        (set? kind) (choice-setting kind)))

(defn- parse-setting
  "[key value] for one `k=v` of filter `f`, or {:error msg}."
  [f token]
  (let [[k v] (str/split token #"=" 2)]
    (if-let [kind (get-in filter-keys [f k])]
      ((setting-parser kind) k v)
      {:error (str "--" (name f) " has no setting " k)})))

(defn- setting? [token] (and (str/includes? token "=") (not (str/starts-with? token "-"))))

(defn- parse-filter
  "Consume a filter flag's settings: [settings errors rest-of-args]."
  [f args]
  (let [[tokens more] (split-with setting? args)
        parsed (map #(parse-setting f %) tokens)]
    [(into {} (remove map? parsed)) (keep :error (filter map? parsed)) more]))

(defn- flag [k] (fn [o _] [(assoc o k true) 0]))

(defn- with-value
  "An option that takes the next argument: (f opts value) -> opts'."
  [what f]
  (fn [o v] [(if v (f o v) (update o :errors conj (str what " needs a value"))) 1]))

(def ^:private options
  "Option -> (fn [opts next-arg] [opts' extra-args-consumed])."
  {"--override" (flag :override)
   "--showSettings" (flag :show)
   "--show-settings" (flag :show)
   "-h" (flag :help)
   "--help" (flag :help)
   "-o" (with-value "--output" #(assoc %1 :out %2))
   "--output" (with-value "--output" #(assoc %1 :out %2))
   "--format" (with-value "--format"
                (fn [o v] (if-let [fmt (formats v)]
                            (assoc o :format fmt)
                            (update o :errors conj (str "unknown format " v " (jpeg, tiff, png, png8)")))))
   "--quality" (with-value "--quality"
                 (fn [o v] (let [q (some-> v parse-number long)]
                             (if (and q (<= 1 q 100))
                               (assoc o :quality q)
                               (update o :errors conj "--quality must be 1..100")))))
   "--upscale" (fn [o _] [(update o :errors conj "--upscale is not supported") 0])})

(defn- parse-option
  "One non-filter option at the head of args: [opts' rest]."
  [o [a v & _ :as args]]
  (if-let [handle (options a)]
    (let [[o' used] (handle o v)]
      [o' (drop (inc used) args)])
    [(update o :errors conj (str "unknown option " a)) (rest args)]))

(defn parse
  "Command-line arguments -> {:files :filters {flag settings} :override :show
   :out :format :quality :help :errors}."
  [args]
  (loop [o {:files [] :filters {} :format :jpeg :quality 95 :errors []}
         [a & more :as args] (seq args)]
    (cond
      (empty? args)
      (cond-> o (and (empty? (:files o)) (not (:help o))) (update :errors conj "no input files"))

      (filter-flags a)
      (let [f (filter-flags a)
            [settings errs rest-args] (parse-filter f more)]
        (recur (-> o (assoc-in [:filters f] settings) (update :errors into errs)) rest-args))

      (str/starts-with? a "-")
      (let [[o' rest-args] (parse-option o args)] (recur o' rest-args))

      :else (recur (update o :files conj a) more))))

;; ---- planning ----------------------------------------------------------------
(def ^:private flag->kind {:noise :denoise :sharpen :sharpen :light :light})

(defn- pct [x] (/ (double x) 100.0))

(def ^:private percent-tones [:contrast :highlights :shadows :whites :blacks :saturation])

(defn- apply-settings
  "Fold one flag's settings into the filter it names."
  [{:keys [strength detail model radius amount exposure] :as settings} current]
  (cond-> (reduce (fn [acc k] (if-let [v (get settings k)] (assoc acc k (pct v)) acc))
                  current percent-tones)
    strength (assoc :opacity (pct strength))
    detail (assoc :detail (pct detail))
    model (assoc :model model)
    radius (assoc :radius radius)
    amount (assoc :amount (pct amount))
    exposure (assoc :exposure exposure)))

(defn plan
  "The filters to run on an image with estimate `est`, in pipeline order:
   [{:kind :opacity :model :detail :amount :radius} ...] (a :light layer
   carries the tone settings; its :exposure may be :auto). Autopilot never
   adds light adjustments."
  [{:keys [filters override]} est]
  (let [chosen (if override
                 {}
                 (into {} (map (fn [{:keys [kind opacity]}] [kind {:opacity opacity}])) (ap/suggest est)))
        chosen (reduce-kv (fn [acc flag settings]
                            (let [kind (flag->kind flag)]
                              (if (false? (:enabled settings))
                                (dissoc acc kind)
                                (assoc acc kind (apply-settings settings (get acc kind {:opacity (d/default-opacity kind)}))))))
                          chosen filters)]
    (->> chosen
         (map (fn [[kind m]] (merge (get-in d/kinds [kind :params]) {:kind kind} m)))
         (sort-by #(get-in d/kinds [(:kind %) :order]))
         vec)))

(defn output-path
  "Where the result of `input` goes: <out or input's dir>/<stem>-almaz.<ext>."
  [{:keys [out format]} input]
  (let [f (java.io.File. input)
        stem (str/replace (.getName f) #"\.[^.]+$" "")
        dir (or out (.getParent f))]
    (str (when dir (str dir "/")) stem "-almaz." (ext format))))

(defn- describe-light [{:keys [exposure] :as f}]
  (apply str
         (if (= :auto exposure) " exposure auto" (format " exposure %+.2g EV" (double exposure)))
         (for [k percent-tones :let [v (get f k)] :when (not= 0.0 v)]
           (str " " (name k) " " (Math/round (* 100.0 v))))))

(defn describe
  "What --showSettings prints for one image."
  [input est filters]
  (str input ": " (ap/describe est)
       (if (empty? filters)
         "\n  no filters"
         (apply str (for [{:keys [kind opacity model detail radius amount] :as f} filters]
                      (str "\n  " (name kind) " " (Math/round (* 100.0 opacity)) "%"
                           (case [kind model]
                             [:denoise :ai] (str " detail " (Math/round (* 100.0 detail)) "%")
                             [:sharpen :ai] " AI deblur"
                             [:sharpen :classic] (str " unsharp radius " radius
                                                      " amount " (Math/round (* 100.0 amount)) "%")
                             [:light :classic] (describe-light f))))))))
