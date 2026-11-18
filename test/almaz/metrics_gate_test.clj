(ns almaz.metrics-gate-test
  "The CI gate over jolt-metrics' JSON report."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.metrics-gate :as gate]))

(defn- finding [path name band & rules]
  {:path path :name name :kind "function" :severity band
   :violations (mapv (fn [r] {:rule r :band band}) rules)})

(def report
  {:findings [(finding "src/a.clj" "f" "warning" "cyclomatic" "lines")
              {:path "src/big.clj" :name "src/big.clj" :kind "file" :severity "warning"
               :violations [{:rule "file_lines" :band "warning"}]}]
   :duplicates [{:a {:path "src/b.clj" :name "y"} :b {:path "src/a.clj" :name "x"}}]
   :cleanup {:unused_requires [] :unused_private [] :unreferenced_public []}})

(deftest issues
  (testing "each warning rule, duplicate pair and cleanup entry is one issue, keyed without line numbers"
    (is (= #{[:violation "src/a.clj" "f" "cyclomatic"]
             [:violation "src/a.clj" "f" "lines"]
             [:violation "src/big.clj" "src/big.clj" "file_lines"]
             [:duplicate "src/a.clj" "x" "src/b.clj" "y"]}
           (gate/issues report))))
  (testing "a duplicate pair is the same issue whichever side comes first"
    (is (= (gate/issues report)
           (gate/issues (update-in report [:duplicates 0] (fn [{:keys [a b]}] {:a b :b a}))))))
  (testing "cleanup entries are issues"
    (is (contains? (gate/issues (assoc-in report [:cleanup :unreferenced_public] [{:path "src/c.clj" :name "g"}]))
                   [:cleanup "unreferenced_public" "src/c.clj" "g"]))))

(deftest check
  (let [baseline (gate/issues report)]
    (testing "the baseline itself passes"
      (is (gate/passed? (gate/check report baseline))))
    (testing "a new warning fails"
      (let [r (gate/check (update report :findings conj (finding "src/a.clj" "g" "warning" "params")) baseline)]
        (is (not (gate/passed? r)))
        (is (= [[:violation "src/a.clj" "g" "params"]] (:new r)))))
    (testing "a critical finding fails even when its rules are in the baseline"
      (let [r (gate/check (assoc-in report [:findings 0 :severity] "critical") baseline)]
        (is (not (gate/passed? r)))
        (is (= ["src/a.clj f"] (:critical r)))))
    (testing "a fixed issue still in the baseline fails until the baseline drops it"
      (let [r (gate/check (assoc report :duplicates []) baseline)]
        (is (not (gate/passed? r)))
        (is (= [[:duplicate "src/a.clj" "x" "src/b.clj" "y"]] (:stale r)))))))
