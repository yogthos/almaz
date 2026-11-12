(ns almaz.models
  "The restoration models: where they live, fetching them, and their sessions.

   Both are official NAFNet checkpoints (megvii-research; MIT, with parts
   from BasicSR under Apache-2.0) exported by native/tools/export_nafnet.py to
   fixed 512x512 ONNX graphs, published on the almaz `models-v1` release with
   pinned SHA-256s and fetched on first use:
     :denoise  NAFNet-SIDD-width64  real sensor noise
     :sharpen  NAFNet-REDS-width64  deblur (motion/defocus, compression)

   One ONNX file per model; the device comes from almaz.infer.ep (Core ML
   through ONNX Runtime on macOS, CUDA/TensorRT/CPU on Linux). Sessions load
   lazily (Core ML compiles a model the first time, then caches it) and are
   shared."
  (:require [almaz.download :as dl]
            [almaz.infer.ep :as ep]
            [almaz.infer.onnxruntime :as ort]
            [almaz.infer.protocol :as p]
            [jolt.ffi :as ffi]))

;; libc: the ONNX Runtime shim reads ALMAZ_COREML_CACHE from the environment.
(ffi/defcfn setenv* "setenv" [:string :string :int32] :int32)

(def ^:private release "https://github.com/yogthos/almaz/releases/download/models-v1/")
(def ^:private release-v2 "https://github.com/yogthos/almaz/releases/download/models-v2/")

(def registry
  {:denoise {:label "NAFNet SIDD" :file "nafnet-sidd-512.onnx"
             :url (str release "nafnet-sidd-512.onnx") :size 464378732
             :sha256 "663360660ecd976b000bd101b71ce403e59c3192ba0a65912d0f04cdf562ee57"
             :inputs ["image"] :outputs ["output"] :block 512 :overlap 64}
   :sharpen {:label "NAFNet REDS" :file "nafnet-reds-512.onnx"
             :url (str release "nafnet-reds-512.onnx") :size 272001345
             :sha256 "d7efe9457c3e164cda36f3b6c166c96f90ee10b05cf92dc141c16dabbf9f4a08"
             :inputs ["image"] :outputs ["output"] :block 512 :overlap 64}
   ;; automatic masks (almaz.segment). Subject: BiRefNet-lite (ZhengPeng7,
   ;; MIT) as onnx-community publishes it, logits for the salient object.
   :subject {:label "BiRefNet lite" :file "birefnet-lite-1024.onnx"
             :url "https://huggingface.co/onnx-community/BiRefNet_lite-ONNX/resolve/main/onnx/model.onnx"
             :size 224005088
             :sha256 "5600024376f572a557870a5eb0afb1e5961636bef4e1e22132025467d0f03333"
             :inputs ["input_image"] :outputs ["output_image"] :block 1024 :channels 1 :logits true
             :mean [0.485 0.456 0.406] :std [0.229 0.224 0.225]
             ;; Core ML rejects its deformable convolutions after ~20 s of
             ;; compiling (and, given explicit pads, runs no faster than the CPU)
             :cpu-only true}
   ;; Scene: UperNet + ConvNeXt-tiny (openmmlab, MIT) on ADE20K, exported by
   ;; native/tools/export_scene_seg.py with its classes summed into sky,
   ;; person and landscape probabilities.
   :scene   {:label "UperNet ConvNeXt" :file "upernet-convnext-tiny-512.onnx"
             :url (str release-v2 "upernet-convnext-tiny-512.onnx") :size 238456147
             :sha256 "255dca951c61301f38d6097508f5b0e30aaf79f4930b2c9ffe4e88ecf5461920"
             :inputs ["image"] :outputs ["groups"] :block 512 :channels 3
             ;; run once or twice per photo: the CPU's 2 s beats Core ML's
             ;; 10 s load (53 s the first time, compiling)
             :cpu-only true}})

(defn model-dir
  "Where models live. Given {:env :os :home :xdg :dev?}: ALMAZ_MODELS when
   set, a source checkout's models/almaz, else the per-user data directory
   (macOS ~/Library/Application Support/almaz/models, Linux
   $XDG_DATA_HOME/almaz/models or ~/.local/share/almaz/models)."
  ([] (model-dir {:env (System/getenv "ALMAZ_MODELS")
                  :os (ep/detect-platform)
                  :home (System/getenv "HOME")
                  :xdg (System/getenv "XDG_DATA_HOME")
                  :dev? (.exists (java.io.File. "models/almaz"))}))
  ([{:keys [env os home xdg dev?]}]
   (cond
     (seq env) env
     dev? "models/almaz"
     (= os :macos) (str home "/Library/Application Support/almaz/models")
     (seq xdg) (str xdg "/almaz/models")
     :else (str home "/.local/share/almaz/models"))))

(defn artifact [id] (str (model-dir) "/" (get-in registry [id :file])))

(defn available? [id]
  (.exists (java.io.File. (artifact id))))

(defonce ^:private verified (atom #{}))

(defn ensure-model!
  "Model `id`'s file, downloaded and verified first when it is missing or
   doesn't match its pinned hash. `progress` gets (bytes total). A file
   verifies once per run (hashing ~0.5 GB isn't free)."
  ([id] (ensure-model! id (fn [_ _])))
  ([id progress]
   (let [{:keys [url sha256 size]} (get registry id)
         dest (artifact id)]
     (when-not (contains? @verified dest)
       (dl/ensure! {:url url :sha256 sha256 :size size :dest dest :progress progress})
       (swap! verified conj dest))
     dest)))

(defn- coreml-cache []
  (let [dir (str (System/getenv "HOME") "/Library/Caches/almaz/coreml")]
    (.mkdirs (java.io.File. dir))
    dir))

(defn devices
  "Device specs to try, best first: for model `id`, only the CPU when the
   registry marks it :cpu-only."
  ([]
   (let [platform (ep/detect-platform)]
     (ep/select-backends platform (if (= platform :macos) {:coreml true} {}))))
  ([id]
   (if (get-in registry [id :cpu-only])
     [{:backend :onnxruntime :ep :cpu}]
     (devices))))

(defonce ^:private sessions (atom {}))
(def ^:private load-lock (Object.))

(defn- load-session [id]
  (let [spec (assoc (get registry id) :artifact (artifact id))]
    (loop [[d & more] (devices id) last-error nil]
      (if-not d
        (throw (ex-info (str "cannot load model " id) {:id id} last-error))
        (let [backend (ort/->OnnxruntimeBackend (:ep d))
              r (try {:backend backend :ep (:ep d) :spec spec
                      :session (p/load-model backend (assoc spec :ep (:ep d)))}
                     (catch Exception e e))]
          (if (instance? Exception r) (recur more r) r))))))

(defn session
  "The loaded session for model `id`: {:backend :session :spec :ep}. Loads
   (and on macOS compiles) on first use; later calls return the same one."
  [id]
  (or (get @sessions id)
      (locking load-lock
        (or (get @sessions id)
            (let [s (do (when (= :macos (ep/detect-platform))
                          (setenv* "ALMAZ_COREML_CACHE" (coreml-cache) 0))
                        (load-session id))]
              (swap! sessions assoc id s)
              s)))))

(defn free-all! []
  (doseq [{:keys [backend session]} (vals @sessions)]
    (p/free-session backend session))
  (reset! sessions {}))
