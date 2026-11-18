(ns almaz.infer.coreml
  "CoreML / ANE backend (macOS) via our Objective-C++ shim
   (native/coreml/lib/libalmazinfer_coreml.dylib).

   The shim wraps the Core ML framework behind a plain C API; ONNX -> .mlmodelc
   conversion happens at build time with coremltools (dev-only), and the app
   ships the compiled .mlmodelc."
  (:require [jolt.ffi :as ffi]
            [almaz.infer.protocol :as p]
            [almaz.infer.ffi :as a]))

(ffi/defcfn coreml-load "almz_coreml_load" [:pointer :pointer] :pointer :blocking)
(ffi/defcfn coreml-predict "almz_coreml_predict"
  [:pointer :int32 :pointer :int32 :pointer :pointer] :int :blocking)
(ffi/defcfn coreml-free "almz_coreml_free" [:pointer] :void)
(ffi/defcfn coreml-free-string "almz_free_string" [:pointer] :void)

(def ^:private shim {:free-string coreml-free-string :backend :coreml})

(defrecord CoremlBackend []
  p/Backend
  (backend-id  [_] :coreml)
  (available?  [_] true)  ; TODO: gate on the shim dylib being loaded
  (load-model  [_ spec]
    (with-open [ar (ffi/confined-arena)]
      (let [h (a/call-checked ar #(coreml-load (ffi/string->ptr ar (:artifact spec)) %) ffi/null? coreml-free-string
                              "coreml load failed" {:backend :coreml :spec spec})]
        {:backend :coreml :handle h :inputs (:inputs spec) :outputs (:outputs spec)})))
  (run-session [_ session tensors]
    (a/run-predict coreml-predict shim (:handle session) (:inputs session) (:outputs session) tensors))
  (run-native [_ session inputs outputs]
    (a/run-native coreml-predict shim (:handle session) inputs outputs))
  (free-session [_ session]
    (when-let [h (:handle session)] (coreml-free h))))
