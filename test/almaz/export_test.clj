(ns almaz.export-test
  "Export settings -> what the writer is asked for, and how the dialog
   describes the result."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.export :as ex]))

(deftest save-options
  (testing "JPEG: quality and colour profile; no compression level"
    (is (= {:format :jpeg :quality 82 :compression 0 :profile :p3}
           (ex/save-opts (assoc ex/defaults :format :jpeg :quality 82 :profile :p3)))))
  (testing "PNG: the bit depth picks the writer, the level is zlib's"
    (is (= {:format :png16 :quality 0 :compression 9 :profile :srgb}
           (ex/save-opts (assoc ex/defaults :format :png :png-depth 16 :png-level 9 :profile :srgb))))
    (is (= :png8 (:format (ex/save-opts (assoc ex/defaults :format :png :png-depth 8))))))
  (testing "TIFF: the compression codec"
    (is (= [:tiff16 0] ((juxt :format :compression) (ex/save-opts (assoc ex/defaults :format :tiff :tiff-compression :none)))))
    (is (= 1 (:compression (ex/save-opts (assoc ex/defaults :format :tiff :tiff-compression :lzw)))))
    (is (= 2 (:compression (ex/save-opts (assoc ex/defaults :format :tiff :tiff-compression :deflate))))))
  (testing "quality is clamped to what the encoder takes"
    (is (= 1 (:quality (ex/save-opts (assoc ex/defaults :format :jpeg :quality -5)))))
    (is (= 100 (:quality (ex/save-opts (assoc ex/defaults :format :jpeg :quality 140)))))))

(deftest extensions
  (is (= ["jpg" "png" "tif"] (map #(ex/ext (assoc ex/defaults :format %)) [:jpeg :png :tiff])))
  (is (= "photo-almaz.jpg" (ex/file-name "photo.NEF" ex/defaults)))
  (is (= "a.b-almaz.png" (ex/file-name "a.b.tif" (assoc ex/defaults :format :png))))
  (testing "the chosen path gets the extension when it is missing"
    (is (= "/x/a.jpg" (ex/with-ext "/x/a" ex/defaults)))
    (is (= "/x/a.JPG" (ex/with-ext "/x/a.JPG" ex/defaults)))))

(deftest human-sizes
  (is (= "820 bytes" (ex/human-size 820)))
  (is (= "94 KB" (ex/human-size 94200)))
  (is (= "1.2 MB" (ex/human-size 1249000)))
  (is (= "38 MB" (ex/human-size 38400000)))
  (is (= "1.05 GB" (ex/human-size 1050000000))))

(deftest info-line
  (is (= "94 KB – 2992 × 1934 px" (ex/info 94200 [2992 1934])))
  (is (= "Estimating… – 2992 × 1934 px" (ex/info nil [2992 1934]))))
