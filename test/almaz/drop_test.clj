(ns almaz.drop-test
  "The kit's file hand-off (native/patches/bui-open-files.patch): file:// URI
   lists from X11 drops, and files that arrive before the app has a handler.
   Needs the BUI library (jolt native)."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.ui.drop :as drop]
            [jolt.ffi :as ffi]))

(ffi/defcfn uri-list-paths* "bk_uri_list_paths" [:pointer :size_t :pointer] :int32)
(ffi/defcfn free-paths*     "bk_free_paths"     [:pointer :int32] :void)
(ffi/defcfn open-files*     "bk_open_files"     [:pointer :pointer :int32 :int32 :int32] :void)

(defn- uri-paths [text]
  (with-open [a (ffi/confined-arena)]
    (let [bs (.getBytes ^String text "UTF-8")
          p (ffi/alloc a (max 1 (alength bs)))
          out (ffi/alloc a :pointer)]
      (ffi/write-array p bs)
      (let [n (uri-list-paths* p (alength bs) out)
            arr (ffi/read out :pointer 0)]
        (try (drop/read-paths arr n)
             (finally (free-paths* arr n)))))))

(deftest uri-lists
  (testing "file URIs, CRLF separated, percent-decoded (UTF-8 too)"
    (is (= ["/home/me/a b.jpg" "/home/me/Ünïcode.NEF"]
           (uri-paths "file:///home/me/a%20b.jpg\r\nfile:///home/me/%C3%9Cn%C3%AFcode.NEF\r\n"))))
  (testing "a host part is skipped; comments, other schemes and blank lines are not files"
    (is (= ["/tmp/x.png"]
           (uri-paths "# from nautilus\r\nfile://localhost/tmp/x.png\r\nhttps://example.com/y.png\r\n\r\n"))))
  (testing "a last line without a newline still counts"
    (is (= ["/tmp/z.tif"] (uri-paths "file:///tmp/z.tif")))))

(deftest early-files-wait-for-a-handler
  (with-open [a (ffi/confined-arena)]
    (let [got (atom [])
          send! (fn [paths]
                  (let [arr (ffi/alloc a (* 8 (count paths)))]
                    (doseq [[i s] (map-indexed vector paths)]
                      (ffi/write arr :pointer (ffi/string->ptr a s) (* 8 i)))
                    (open-files* ffi/null arr (count paths) -1 -1)))]
      (drop/clear!)
      (send! ["/launch/one.jpg" "/launch/two.NEF"])   ; Open With at launch
      (send! ["/launch/three.png"])
      (is (= [] @got))
      (drop/install! #(swap! got conj %))
      (is (= [["/launch/one.jpg" "/launch/two.NEF"] ["/launch/three.png"]] @got)
          "queued batches are handed over in order once there is a handler")
      (send! ["/later.tif"])
      (is (= ["/later.tif"] (peek @got)) "later ones go straight to it")
      (drop/clear!))))
