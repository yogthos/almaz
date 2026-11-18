(ns almaz.version-test
  "The app's version has one source, almaz.version, read by the UI and by
   the packagers (which name the bundle and its Info.plist after it)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [almaz.version :as version]))

(defn- clj-files [dir]
  (->> (file-seq (io/file dir)) (map str) (filter #(re-find #"\.clj$" %))))

(deftest one-source
  (testing "it's a semantic version"
    (is (re-matches #"\d+\.\d+\.\d+" version/version)))
  (testing "nothing else spells out a version of its own"
    (is (= [] (for [f (concat (clj-files "src") (clj-files "native/tools"))
                    :when (not (re-find #"almaz/version\.clj$" f))
                    :when (re-find #"\(def version \"" (slurp f))]
                f))))
  (testing "both packagers read it"
    (doseq [f ["native/tools/package_macos.clj" "native/tools/package_linux.clj"]]
      (is (re-find #"src/almaz/version\.clj" (slurp f)) f))))
