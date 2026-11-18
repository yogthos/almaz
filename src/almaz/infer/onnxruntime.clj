(ns almaz.infer.onnxruntime
  "ONNX Runtime backend (CPU + Linux GPU execution providers) via our C++ shim
   (native/onnxruntime/lib/libalmazinfer_onnxruntime.dylib).

   Same ONNX graph every backend loads; here it runs on the CPU EP (brew build).
   A GPU-enabled ORT build adds the CUDA/TensorRT/OpenVINO EPs — the shim's ep
   switch is the extension point."
  (:require [jolt.ffi :as ffi]
            [almaz.infer.protocol :as p]
            [almaz.infer.ffi :as a]))

(ffi/defcfn ort-load "almz_ort_load" [:pointer :pointer :pointer] :pointer :blocking)
(ffi/defcfn ort-predict "almz_ort_predict"
  [:pointer :int32 :pointer :int32 :pointer :pointer] :int :blocking)
(ffi/defcfn ort-free "almz_ort_free" [:pointer] :void)
(ffi/defcfn ort-free-string "almz_ort_free_string" [:pointer] :void)

(def ^:private shim {:predict ort-predict :free-string ort-free-string :backend :onnxruntime})

(defrecord OnnxruntimeBackend [ep]
  p/Backend
  (backend-id  [_] :onnxruntime)
  (available?  [_] true)  ; TODO: gate on the shim dylib being loaded
  (load-model  [_ spec]
    (with-open [ar (ffi/confined-arena)]
      (let [load #(ort-load (ffi/string->ptr ar (:artifact spec)) (ffi/string->ptr ar (name (:ep spec :cpu))) %)
            h (a/call-checked ar load
                              {:failed? ffi/null? :free-string ort-free-string
                               :message "onnxruntime load failed" :data {:backend :onnxruntime :spec spec}})]
        {:backend :onnxruntime :ep (:ep spec :cpu)
         :handle h :inputs (:inputs spec) :outputs (:outputs spec)})))
  (run-session [_ session tensors]
    (a/run-predict shim session tensors))
  (run-native [_ session inputs outputs]
    (a/run-native shim session inputs outputs))
  (free-session [_ session]
    (when-let [h (:handle session)] (ort-free h))))
