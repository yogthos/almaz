(ns almaz.metrics-gate
  "CI gate over jolt-metrics' JSON report: no critical findings, and no
   warning, near-duplicate or cleanup entry that the baseline
   (.jolt-metrics-baseline.edn) doesn't list. Fixed issues must leave the
   baseline too, so they can't come back unnoticed.

     jolt -M:metrics report.json            check
     jolt -M:metrics report.json --update   rewrite the baseline from the report"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]))

(def baseline-file ".jolt-metrics-baseline.edn")

(defn- violations [{:keys [path name violations]}]
  (for [{:keys [rule]} violations] [:violation path name rule]))

(defn- duplicate [{:keys [a b]}]
  (let [[x y] (sort-by (juxt :path :name) [a b])]
    [:duplicate (:path x) (:name x) (:path y) (:name y)]))

(defn- cleanups [cleanup]
  (for [[category entries] cleanup, {:keys [path name]} entries]
    [:cleanup (clojure.core/name category) path name]))

(defn issues
  "Every issue in the report, keyed by file, name and rule (not line, so
   edits elsewhere in a file don't move them)."
  [{:keys [findings duplicates cleanup]}]
  (set (concat (mapcat violations findings)
               (map duplicate duplicates)
               (cleanups cleanup))))

(defn check
  "{:critical [\"path name\"] :new [issue] :stale [issue]} for the report
   against the baseline's issues."
  [report baseline]
  (let [now (issues report)
        baseline (set baseline)]
    {:critical (vec (for [f (:findings report) :when (= "critical" (:severity f))]
                      (str (:path f) " " (:name f))))
     :new (vec (sort (remove baseline now)))
     :stale (vec (sort (remove now baseline)))}))

(defn passed? [{:keys [critical new stale]}]
  (every? empty? [critical new stale]))

(defn- report-of [path] (json/read-str (slurp path) :key-fn keyword))

(defn- print-failures [{:keys [critical new stale]}]
  (doseq [c critical] (println "critical:" c))
  (doseq [i new] (println "new:" (str/join " " (rest i))))
  (doseq [i stale] (println "fixed, remove from the baseline:" (str/join " " (rest i))))
  (println (str "\nFix the new findings, or run `jolt -M:metrics <report.json> --update` to accept them "
                "(and drop fixed ones) in " baseline-file ".")))

(defn -main [report-path & args]
  (let [report (report-of report-path)]
    (if (some #{"--update"} args)
      (do (spit baseline-file (with-out-str (pprint (vec (sort (issues report))))))
          (println "wrote" baseline-file))
      (let [result (check report (edn/read-string (slurp baseline-file)))]
        (if (passed? result)
          (println "metrics gate: ok," (count (issues report)) "known issues, no new ones")
          (do (print-failures result)
              (System/exit 1)))))))
