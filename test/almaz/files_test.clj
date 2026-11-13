(ns almaz.files-test
  "Which files a drop or an Open With hands the app."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.files :as f]))

(defn- tmp-dir []
  (let [d (java.io.File. (str (or (System/getenv "TMPDIR") "/tmp/") "almaz-files-" (System/nanoTime)))]
    (.mkdirs d)
    (str d)))

(defn- touch [path] (spit path "") path)

(deftest supported
  (is (f/supported? "/a/b.NEF"))
  (is (f/supported? "/a/b.jpeg"))
  (is (f/supported? "/a/b.TIF"))
  (is (not (f/supported? "/a/b.txt")))
  (is (not (f/supported? "/a/noext"))))

(deftest expand
  (let [d (tmp-dir)
        sub (str d "/shoot")]
    (.mkdirs (java.io.File. sub))
    (let [a (touch (str d "/a.jpg"))
          notes (touch (str d "/notes.txt"))
          z (touch (str sub "/z.NEF"))
          b (touch (str sub "/b.png"))
          _ (touch (str sub "/.hidden.jpg"))
          _ (.mkdirs (java.io.File. (str sub "/nested")))
          _ (touch (str sub "/nested/deep.jpg"))]
      (testing "files are kept in order, unsupported ones dropped"
        (is (= [a] (f/expand [notes a]))))
      (testing "a folder gives its images, sorted by name, not hidden ones or subfolders"
        (is (= [a b z] (f/expand [a sub]))))
      (testing "no duplicates; missing paths are skipped"
        (is (= [a] (f/expand [a a (str d "/gone.jpg")])))))))
