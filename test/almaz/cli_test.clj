(ns almaz.cli-test
  "Command-line parsing and planning (pure), and a small batch run."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.batch :as batch]
            [almaz.cli :as cli]
            [almaz.pixels :as px]))

(deftest batch-mode
  (is (cli/batch? ["--noise" "a.NEF"]))
  (is (cli/batch? ["-h"]))
  (is (not (cli/batch? ["a.NEF" "b.jpg"])))
  (is (not (cli/batch? [])))
  (testing "the process serial number older macOS passes a Finder-launched app isn't an option"
    (is (not (cli/batch? ["-psn_0_12345"])))))

(deftest parsing
  (testing "filters with key=value settings, files, output options"
    (let [o (cli/parse ["--noise" "strength=40" "detail=10" "--sharpen" "model=classic" "radius=2"
                        "-o" "out" "--format" "tiff" "--quality" "80" "a.NEF" "b.jpg"])]
      (is (= {:noise {:strength 40.0 :detail 10.0} :sharpen {:model :classic :radius 2.0}} (:filters o)))
      (is (= ["a.NEF" "b.jpg"] (:files o)))
      (is (= "out" (:out o)))
      (is (= :tiff16 (:format o)))
      (is (= 80 (:quality o)))
      (is (empty? (:errors o)))))
  (testing "bare flags and flags that turn a filter off"
    (let [o (cli/parse ["--noise" "--sharpen" "enabled=false" "--override" "--showSettings" "x.png"])]
      (is (= {:noise {} :sharpen {:enabled false}} (:filters o)))
      (is (:override o))
      (is (:show o))))
  (testing "defaults"
    (let [o (cli/parse ["--noise" "x.png"])]
      (is (= :jpeg (:format o)))
      (is (= 95 (:quality o)))
      (is (nil? (:out o)))))
  (testing "mistakes are reported, not guessed at"
    (is (seq (:errors (cli/parse ["--noise" "strength=lots" "x.png"]))))
    (is (seq (:errors (cli/parse ["--sharpen" "model=fancy" "x.png"]))))
    (is (seq (:errors (cli/parse ["--noise" "colour=1" "x.png"]))))
    (is (seq (:errors (cli/parse ["--format" "gif" "x.png"]))))
    (is (seq (:errors (cli/parse ["--frobnicate" "x.png"]))))
    (is (seq (:errors (cli/parse ["--noise"]))) "no files")
    (is (seq (:errors (cli/parse ["--upscale" "x.png"]))) "upscale isn't implemented"))
  (testing "help needs no files"
    (let [o (cli/parse ["--help"])]
      (is (:help o))
      (is (empty? (:errors o))))))

(def noisy-soft {:noise 0.03 :sharpness 0.1})

(deftest planning
  (testing "autopilot decides, the command line adjusts"
    (is (= [:denoise :sharpen] (mapv :kind (cli/plan {:filters {}} noisy-soft))))
    (let [[d s] (cli/plan {:filters {:noise {:strength 40}}} noisy-soft)]
      (is (= 0.4 (:opacity d)))
      (is (= :sharpen (:kind s)))))
  (testing "enabled=false drops a filter autopilot chose"
    (is (= [:sharpen] (mapv :kind (cli/plan {:filters {:noise {:enabled false}}} noisy-soft)))))
  (testing "a flag adds a filter autopilot didn't choose"
    (is (= [:denoise] (mapv :kind (cli/plan {:filters {:noise {}}} {:noise 0.001 :sharpness 0.9})))))
  (testing "--override: exactly the filters given, defaults for what isn't said"
    (let [[s] (cli/plan {:override true :filters {:sharpen {:model :classic}}} noisy-soft)]
      (is (= [:sharpen] (mapv :kind (cli/plan {:override true :filters {:sharpen {}}} noisy-soft))))
      (is (= :classic (:model s)))
      (is (= 0.5 (:opacity s)))))
  (testing "filters stay in pipeline order whatever the flag order"
    (is (= [:denoise :sharpen] (mapv :kind (cli/plan {:override true :filters {:sharpen {} :noise {}}} {}))))))

(deftest output-paths
  (is (= "/p/DSC_1-almaz.jpg" (cli/output-path {:format :jpeg} "/p/DSC_1.NEF")))
  (is (= "out/DSC_1-almaz.tif" (cli/output-path {:format :tiff16 :out "out"} "/p/DSC_1.NEF")))
  (is (= "DSC_1-almaz.png" (cli/output-path {:format :png16} "DSC_1.NEF"))))

(deftest describing
  (is (string? (cli/describe "a.NEF" noisy-soft (cli/plan {:filters {}} noisy-soft)))))

(deftest a-small-batch
  (testing "a classic sharpen over a PNG, end to end, no models needed"
    (let [dir (str (or (System/getenv "TMPDIR") "/tmp/") "almaz-cli-" (System/nanoTime))
          src (str dir "/in.png")
          img (px/new-image 64 48)]
      (.mkdirs (java.io.File. dir))
      (px/save-image! img [] {:path src :format :png16 :quality 90})
      (px/free-image! img)
      (let [result (batch/run-batch! (cli/parse ["--override" "--sharpen" "model=classic" "--format" "png"
                                           "-o" dir src]))]
        (is (= {:ok 1 :failed 0} (select-keys result [:ok :failed])))
        (let [out (px/load-image (str dir "/in-almaz.png"))]
          (is (= [64 48] [(px/width out) (px/height out)]))
          (px/free-image! out)))
      (testing "a missing input fails that file, not the run"
        (let [r (batch/run-batch! (cli/parse ["--override" "--sharpen" "model=classic" "-o" dir
                                        (str dir "/nope.png") src]))]
          (is (= {:ok 1 :failed 1} (select-keys r [:ok :failed]))))))))

(deftest light-adjustments
  (testing "--light takes the adjustments: exposure in EV (or auto), the rest -100..100"
    (let [o (cli/parse ["--light" "exposure=1.5" "contrast=20" "saturation=-10" "a.NEF"])]
      (is (= {:light {:exposure 1.5 :contrast 20.0 :saturation -10.0}} (:filters o)))
      (is (empty? (:errors o))))
    (is (= {:light {:exposure :auto}} (:filters (cli/parse ["--light" "exposure=auto" "a.NEF"]))))
    (is (seq (:errors (cli/parse ["--light" "exposure=bright" "a.NEF"]))))
    (is (seq (:errors (cli/parse ["--light" "exposure=7" "a.NEF"]))) "exposure is -5..5")
    (is (seq (:errors (cli/parse ["--light" "contrast=150" "a.NEF"]))) "the rest is -100..100"))
  (testing "never applied unless asked for, autopilot or not"
    (is (not-any? #(= :light (:kind %)) (cli/plan {:filters {}} noisy-soft))))
  (testing "it goes on top, neutral where not given"
    (let [ls (cli/plan {:filters {:light {:exposure 1.5 :contrast 20.0}}} noisy-soft)
          l (last ls)]
      (is (= [:denoise :sharpen :light] (mapv :kind ls)))
      (is (= 1.5 (:exposure l)))
      (is (= 0.2 (:contrast l)))
      (is (= 0.0 (:shadows l)))
      (is (= 1.0 (:opacity l)))))
  (testing "--showSettings says what it does"
    (is (re-find #"light .*exposure \+1.5" (cli/describe "a.NEF" noisy-soft
                                                         (cli/plan {:override true :filters {:light {:exposure 1.5}}} noisy-soft)))))
  (testing "end to end: brightens the result; exposure=auto measures the image"
    (let [dir (str (or (System/getenv "TMPDIR") "/tmp/") "almaz-light-" (System/nanoTime))
          src (str dir "/in.png")
          img (px/new-image 16 16)]
      (.mkdirs (java.io.File. dir))
      (px/set-pixels! img (float-array (* 16 16 3) 0.1))
      (px/save-image! img [] {:path src :format :png16 :quality 90})
      (px/free-image! img)
      (doseq [[ev expect] [["1" #(< 0.13 % 0.2)] ["auto" #(< 0.55 % 0.65)]]]
        (is (= 1 (:ok (batch/run-batch! (cli/parse ["--override" "--light" (str "exposure=" ev)
                                                    "--format" "png" "-o" dir src])))))
        (let [out (px/load-image (str dir "/in-almaz.png"))]
          (is (expect (first (px/pixel out 8 8))) ev)
          (px/free-image! out))))))
