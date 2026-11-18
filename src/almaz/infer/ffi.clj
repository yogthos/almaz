(ns almaz.infer.ffi
  "Shared FFI marshalling for the inference backends.

   The C shims (native/coreml, native/onnxruntime) both speak the `almz_tensor`
   struct — this namespace holds that struct's layout (the wire contract) plus
   the helpers that move Clojure float vectors into and out of C buffers."
  (:require [jolt.ffi :as ffi]))

(def tensor-layout
  "Layout of the C `almz_tensor` struct. Must match native/*/almazinfer_*.h."
  (ffi/layout [:struct [[:name :pointer]
                        [:shape [:array :int64 4]]
                        [:rank :int32]
                        [:data :pointer]
                        [:count :size_t]]]))

(def ^:private struct-size (ffi/sizeof tensor-layout))

(defn- pad-shape [shape]
  (vec (take 4 (concat (mapv long shape) (repeat 0)))))

(defn write-floats!
  "Allocate a C float buffer in `arena` and copy the seq `data` into it. Returns
   the pointer."
  [arena data]
  (let [n (count data)
        p (ffi/alloc arena (* 4 n))]
    (ffi/write-array p :float (float-array data))
    p))

(defn read-floats
  "Read `n` floats from pointer `p` into a Clojure vector."
  [p n]
  (vec (ffi/read-array p :float n)))

(defn write-tensor-array!
  "Allocate a contiguous array of `almz_tensor` structs in `arena` and fill them
   from `entries`, a seq of {:name :shape :data}. Returns the base pointer."
  [arena entries]
  (let [n (count entries)
        arr (ffi/alloc arena (* struct-size n))]
    (doseq [[i e] (map-indexed vector entries)]
      (let [datap (write-floats! arena (:data e))
            namep (ffi/string->ptr arena (:name e))
            shape (pad-shape (:shape e))]
        (ffi/write arr tensor-layout
                   {:name namep :shape shape :rank (count (:shape e))
                    :data datap :count (count (:data e))}
                   (* i struct-size))))
    arr))

(defn native-tensor-array!
  "An `almz_tensor` array over caller-owned buffers: `entries` is a seq of
   {:name :shape :ptr :count} (outputs may omit :shape)."
  [arena entries]
  (let [arr (ffi/alloc arena (* struct-size (max 1 (count entries))))]
    (doseq [[i e] (map-indexed vector entries)]
      (ffi/write arr tensor-layout
                 {:name (ffi/string->ptr arena (:name e))
                  :shape (pad-shape (or (:shape e) []))
                  :rank (count (:shape e))
                  :data (:ptr e) :count (:count e)}
                 (* i struct-size)))
    arr))

(defn alloc-output-array!
  "Allocate `out-names` output `almz_tensor` structs, each with a buffer of
   `out-count` floats (caller must pre-size; v1 image-to-image = input size)."
  [arena out-names out-count]
  (let [n (count out-names)
        arr (ffi/alloc arena (* struct-size n))]
    (doseq [[i nm] (map-indexed vector out-names)]
      (let [datap (ffi/alloc arena (* 4 out-count))
            namep (ffi/string->ptr arena nm)]
        (ffi/write arr tensor-layout
                   {:name namep :shape [0 0 0 0] :rank 0
                    :data datap :count out-count}
                   (* i struct-size))))
    arr))

(defn read-output-array!
  "Read output tensor structs back into a map name -> {:data <vec> :shape <vec>}."
  [arr out-names]
  (into {} (map-indexed
            (fn [i nm]
              (let [m (ffi/read arr tensor-layout (* i struct-size))
                    datap (:data m)
                    count (long (:count m))
                    rank (int (:rank m))
                    shape (vec (take rank (:shape m)))]
                [nm {:data (read-floats datap count) :shape shape}]))
            out-names)))

(defn read-and-free-error
  "Read the shim's error out-param and free it with `free-string-fn`. nil if none."
  [errslot free-string-fn]
  (let [ep (ffi/read errslot :pointer 0)]
    (when-not (ffi/null? ep)
      (let [msg (ffi/ptr->string ep)]
        (free-string-fn ep)
        msg))))

(defn call-checked
  "(f errslot) with a fresh error out-param in arena `ar`. When (failed? r),
   throw the shim's message (freed with `free-string`) or `message`, with
   ex-data `data`."
  [ar f {:keys [failed? free-string message data]}]
  (let [errslot (ffi/alloc ar :pointer)]
    (ffi/write errslot :pointer ffi/null 0)
    (let [r (f errslot)]
      (if (failed? r)
        (throw (ex-info (or (read-and-free-error errslot free-string) message) data))
        r))))

(defn- check-predict
  "Run a backend shim's predict over the marshaled arrays; throw when it fails."
  [ar {:keys [predict free-string backend]} [handle n-in in-arr n-out out-arr] message]
  (call-checked ar #(predict handle n-in in-arr n-out out-arr %)
                {:failed? (complement zero?) :free-string free-string
                 :message message :data {:backend backend}}))

(defn run-predict
  "A backend's run-session: marshal `tensors` (by input name) in, run the
   shim's predict, read outputs back. `shim` is {:predict f :free-string f
   :backend kw}, predict taking (handle n-in in-arr n-out out-arr errslot)."
  [shim {:keys [handle inputs outputs]} tensors]
  (let [in-entries (mapv (fn [n] (select-keys (tensors n) [:name :shape :data])) inputs)
        out-count (count (:data (tensors (first inputs))))]  ; v1: same-size I/O
    (with-open [ar (ffi/confined-arena)]
      (let [out-arr (alloc-output-array! ar outputs out-count)]
        (check-predict ar shim [handle (count inputs) (write-tensor-array! ar in-entries) (count outputs) out-arr]
                       (str (name (:backend shim)) " predict failed"))
        (read-output-array! out-arr outputs)))))

(defn run-native
  "A backend's run-native: caller-owned buffers in and out, nothing copied."
  [shim {:keys [handle]} inputs outputs]
  (with-open [ar (ffi/confined-arena)]
    (check-predict ar shim [handle (count inputs) (native-tensor-array! ar inputs)
                            (count outputs) (native-tensor-array! ar outputs)]
                   "predict failed")
    nil))
