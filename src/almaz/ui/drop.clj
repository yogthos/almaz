(ns almaz.ui.drop
  "Files handed to the app by the desktop: dropped on the window, or opened
   with Almaz (Finder's Open With, the Dock icon). The BUI kit reports both
   through BK_window_set_open_files (native/patches/bui-open-files.patch),
   keeping any that arrive before a handler is installed. FFI only."
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn set-open-files* "BK_window_set_open_files" [:pointer :pointer] :void)

(defn read-paths
  "`n` C strings from the pointer array at `arr`."
  [arr n]
  (mapv #(ffi/ptr->string (ffi/read arr :pointer (* 8 %))) (range n)))

(defonce ^:private callback (atom nil))   ; kept alive: the kit holds it

(defn install!
  "Call (f paths) with each batch of files the desktop hands over, on the
   window loop's thread. Batches that arrived earlier are handed over now."
  [f]
  (let [cb (ffi/foreign-callable
             (fn [_data _win paths n _x _y] (f (read-paths paths n)))
             [:pointer :pointer :pointer :int :int :int] :void :collect-safe)]
    (reset! callback cb)
    (set-open-files* cb ffi/null)))

(defn clear!
  "Drop the handler (files are kept again until the next install!)."
  []
  (set-open-files* ffi/null ffi/null)
  (reset! callback nil))
