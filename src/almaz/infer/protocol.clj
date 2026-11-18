(ns almaz.infer.protocol
  "The backend-agnostic inference interface.

   Feature code (tile, denoise, sharpen) is written against this protocol and
   never cares whether a model runs on CoreML/ANE, a CUDA/OpenCL GPU via ONNX
   Runtime, or the CPU. One ONNX graph is the single source of truth; each
   backend consumes the same graph.

   A *Tensor* is a plain map:  {:name \"x\" :shape [h w c] :dtype :f32
                                :data <flat f32 vector>}
   A *ModelSpec* is opaque to the protocol and passed through untouched; each
   backend decides what keys it needs (e.g. :artifact path, :kind).")

(defprotocol Backend
  (backend-id  [this]
    "Keyword identifying the backend: :coreml, :onnxruntime, ...")
  (available?  [this]
    "True if this backend can run on the current host (dylib present, device found).")
  (load-model  [this spec]
    "Load a compiled model `spec`; returns an opaque session.")
  (run-session [this session tensors]
    "Run `session` over `tensors` (map name->Tensor); returns map name->Tensor.")
  (run-native [this session inputs outputs]
    "Run `session` on caller-owned native float buffers, no copies through
     Clojure. `inputs` is a seq of {:name :shape :ptr :count}, `outputs` of
     {:name :ptr :count}; the outputs are written in place. Returns nil.")
  (free-session [this session]
    "Release the session and any buffers it owns."))
