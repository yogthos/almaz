;; Build every native library the app loads: `jolt native`.
;;
;; 1. macOS: `brew install` whichever of the Homebrew packages is missing.
;; 2. Fetch LibRaw and the BUI kit into .deps/, pinned to the commits CI uses,
;;    and build them (LibRaw's static archive, BUI's static archives linked
;;    into one shared library). Skipped when already built at that commit.
;; 3. Run native/<shim>/build.sh for each of our shims against those.
;;
;; deps.edn lists .deps/bui/lib/libbui.* as a :jolt/native candidate, so the
;; app finds BUI without DYLD_LIBRARY_PATH. On Linux the system packages come
;; from apt (see ci.yml) and the ONNX Runtime shim is built only when
;; ONNXRUNTIME_INC / ONNXRUNTIME_LIB point at its release tarball.
(require '[clojure.string :as str]
         '[jolt.process :as p])

;; keep in step with .github/workflows/ci.yml
(def libraw-sha "0b87f5c907d7ee0e7641dd1b6a6c9140b9451922")
(def bui-sha "79be8e30bbbc28c8224274d342081e8f522d34cf")
(def brew-pkgs ["cmake" "jpeg-turbo" "libpng" "libtiff" "onnxruntime"])

(def root (.getCanonicalPath (java.io.File. ".")))
(def deps-dir (str root "/.deps"))
(def libraw-dir (str deps-dir "/libraw"))
(def bui-dir (str deps-dir "/bui"))

(def mac? (str/starts-with? (str/lower-case (System/getProperty "os.name")) "mac"))
(def jobs (str (.availableProcessors (Runtime/getRuntime))))

(defn log [& xs] (apply println "native:" xs) (flush))
(defn exists? [path] (.exists (java.io.File. path)))

(defn sh!
  "Run a command with inherited stdio; throw when it fails. opts as babashka.process."
  [opts & args]
  (let [[opts args] (if (map? opts) [opts args] [{} (cons opts args)])]
    (apply p/shell opts (map str args))))

(defn ok? [& args]
  (zero? (:exit (apply p/shell {:out :string :err :string :continue true} args))))

(defn brew! []
  (when mac?
    (when-not (ok? "which" "brew")
      (throw (ex-info "Homebrew not found; install it from https://brew.sh" {})))
    (doseq [pkg brew-pkgs]
      (when-not (ok? "brew" "list" "--versions" pkg)
        (log "brew install" pkg)
        (sh! "brew" "install" pkg)))))

(defn head [dir]
  (when (exists? (str dir "/.git"))
    (str/trim (:out (p/shell {:out :string :err :string :continue true}
                             "git" "-C" dir "rev-parse" "HEAD")))))

(defn fetch!
  "Shallow-fetch one commit of url into dir, unless dir is already at it.
  Returns true when it fetched."
  [url dir sha]
  (when-not (= sha (head dir))
    (log "fetching" url "@" (subs sha 0 8))
    (sh! "rm" "-rf" dir)
    (sh! "git" "init" "-q" dir)
    (sh! "git" "-C" dir "fetch" "-q" "--depth" "1" url sha)
    (sh! "git" "-C" dir "checkout" "-q" "FETCH_HEAD")
    true))

(defn libraw! []
  (let [fetched (fetch! "https://github.com/LibRaw/LibRaw" libraw-dir libraw-sha)
        archive (str libraw-dir "/lib/libraw_r.a")]
    (when (or fetched (not (exists? archive)))
      (log "building LibRaw")
      (apply sh! {:dir libraw-dir} "make" "-f" "Makefile.dist" (str "-j" jobs) "library"
             ;; it ends up inside a shared library
             (when-not mac? ["CFLAGS=-O3 -I. -w -fPIC -DUSE_ZLIB"])))))

(def bui-patches
  "Our changes to BUI (native/patches/bui-*.patch), applied over the pinned
  commit: trackpad scrolling that stops at a scroll view's ends."
  (sort (filter #(re-find #"^bui-.*[.]patch$" %) (.list (java.io.File. (str root "/native/patches"))))))

(defn- patch-stamp
  "What the patched tree was built from: the commit and the patches' text."
  []
  (str bui-sha "\n" (str/join "\n" (map #(slurp (str root "/native/patches/" %)) bui-patches))))

(defn bui! []
  (let [stamp-file (str bui-dir "/.almaz-patches")
        stale? (not= (patch-stamp) (when (exists? stamp-file) (slurp stamp-file)))
        _ (when (and stale? (= bui-sha (head bui-dir)))
            (sh! "git" "-C" bui-dir "checkout" "-q" "--" "."))
        fetched (fetch! "https://github.com/ppmpreetham/BUI" bui-dir bui-sha)
        lib (str bui-dir "/lib/libbui." (if mac? "dylib" "so"))
        b (str bui-dir "/build")
        archives [(str b "/kit/libbui_kit.a") (str b "/gpu/libbui_gpu.a") (str b "/core/libbui_core.a")]]
    (when (or fetched stale? (not (exists? lib)))
      (when (or fetched stale?)
        (doseq [p bui-patches]
          (log "applying" p)
          (sh! "git" "-C" bui-dir "apply" (str root "/native/patches/" p))))
      (log "building BUI")
      (sh! "cmake" "-S" bui-dir "-B" b "-DCMAKE_BUILD_TYPE=Release"
           "-DCMAKE_POSITION_INDEPENDENT_CODE=ON")
      (sh! "cmake" "--build" b "--parallel")
      (sh! "mkdir" "-p" (str bui-dir "/lib"))
      (if mac?
        (apply sh! "c++" "-shared" "-o" lib
               (concat (mapcat #(vector "-force_load" %) archives)
                       ["-framework" "Cocoa" "-framework" "IOKit" "-framework" "CoreVideo"
                        "-framework" "AVFoundation" "-framework" "CoreMedia" "-lm" "-lpthread"]))
        (apply sh! "c++" "-shared" "-o" lib "-Wl,--whole-archive"
               (concat archives ["-Wl,--no-whole-archive" "-lm" "-lpthread" "-ldl"])))
      (spit stamp-file (patch-stamp)))))

(defn shims! []
  (sh! "native/img/build.sh")
  (sh! {:extra-env {"LIBRAW_DIR" libraw-dir}} "native/raw/build.sh")
  (if (or mac? (System/getenv "ONNXRUNTIME_INC"))
    (sh! "native/onnxruntime/build.sh")
    (log "skipping the ONNX Runtime shim: set ONNXRUNTIME_INC / ONNXRUNTIME_LIB"))
  (when mac?
    (sh! "native/coreml/build.sh")))

(brew!)
(libraw!)
(bui!)
(shims!)
(log "done")
