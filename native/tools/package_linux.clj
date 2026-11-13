;; Package Almaz for Linux: `jolt release` (dispatched from package.clj).
;;
;; Layout (target/Almaz-linux-<arch>/, tarred as
;; target/Almaz-<version>-linux-<arch>.tar.gz):
;;
;;   bin/Almaz         launcher: puts lib/ on LD_LIBRARY_PATH, execs almaz-bin
;;   bin/almaz-bin     the app built by `jolt build`
;;   lib/              almaz's native libraries, the BUI kit, and everything
;;                     they load from outside the distro's system dirs
;;                     (ONNX Runtime, LibRaw's deps, ...)
;;   share/applications/almaz.desktop
;;   share/doc/almaz/LICENSE
;;
;; Copying follows DT_NEEDED (ldd) recursively and preserves each library's
;; SONAME as a symlink, so the loader finds e.g. libonnxruntime.so.1. A library
;; in /lib, /lib64, /usr/lib or /usr/lib64 is treated as the distro's and left
;; alone (glibc, libstdc++, X11/GL, the image codecs). When patchelf is
;; installed, bundled libraries and the executable also get an $ORIGIN rpath,
;; so running bin/almaz-bin directly works too.
(require '[clojure.string :as str]
         '[jolt.process :as p])

(defn sh*
  "Run a command, returning its stdout; throw with its stderr when it fails."
  [& args]
  (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true} (map str args))]
    (when-not (zero? exit)
      (throw (ex-info (str (str/join " " args) " failed:\n" err) {:exit exit})))
    out))

(defn sh [& args] (apply sh* args))
(defn log [& xs] (apply println "release:" xs) (flush))
(defn file [& parts] (java.io.File. (str/join "/" parts)))
(defn exists? [path] (.exists (java.io.File. path)))
(defn base-name [path] (.getName (java.io.File. path)))
(defn real-path [path] (str/trim (sh "realpath" path)))
(defn have? [cmd] (zero? (:exit (p/shell {:out :string :err :string :continue true} "sh" "-c" (str "command -v " cmd)))))

(def here (str (.getParent (java.io.File. *file*))))
(def version (:version (clojure.edn/read-string (slurp (str here "/version.edn")))))

(def arch (case (str/trim (sh* "uname" "-m"))
            ("x86_64" "amd64") "x86_64"
            ("aarch64" "arm64") "aarch64"
            (str/trim (sh* "uname" "-m"))))

(def app (str "target/Almaz-linux-" arch))
(def bindir (str app "/bin"))
(def libdir (str app "/lib"))
(def sharedir (str app "/share"))
(def exe-name "Almaz")
(def bin-name "almaz-bin")

(def launcher
  "#!/bin/sh
# Almaz launcher: load the bundled libraries, then run the app.
here=\"$(cd \"$(dirname \"$0\")\" && pwd)\"
root=\"$(cd \"$here/..\" && pwd)\"
export LD_LIBRARY_PATH=\"$root/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"
exec \"$here/almaz-bin\" \"$@\"
")

(def desktop-entry
  "[Desktop Entry]
Type=Application
Name=Almaz
GenericName=Photo Editor
Comment=AI denoise and sharpen for photos
Exec=Almaz %F
Terminal=false
Categories=Graphics;Photography;
MimeType=image/jpeg;image/png;image/tiff;image/x-dcraw;image/x-adobe-dng;image/x-nikon-nef;image/x-nikon-nrw;image/x-sony-arw;image/x-canon-cr2;image/x-canon-cr3;image/x-fuji-raf;image/x-olympus-orf;image/x-panasonic-rw2;image/x-pentax-pef;image/x-samsung-srw;
StartupNotify=true
")

(def own-libs
  ["native/img/lib/libalmazimg.so"
   "native/onnxruntime/lib/libalmazinfer_onnxruntime.so"
   "native/raw/lib/libalmazinfer_raw.so"])

(defn bui-lib
  "The BUI kit shared object: $BUI_LIB, or the usual build locations."
  []
  (or (some #(when (and % (exists? %)) %)
            [(System/getenv "BUI_LIB")
             ".deps/bui/lib/libbui.so"
             (str (System/getenv "HOME") "/deps/bui/lib/libbui.so")
             (str (System/getenv "HOME") "/src/bui-src/lib/libbui.so")
             (str (System/getenv "HOME") "/src/jlt-commons/glimmer-bui/lib/libbui.so")])
      (throw (ex-info "libbui.so not found; set BUI_LIB" {}))))

(def system-dirs
  ["/lib/" "/lib32/" "/lib64/" "/usr/lib/" "/usr/lib32/" "/usr/lib64/" "/usr/libexec/"])

(defn system-lib? [path]
  (or (some #(str/starts-with? path %) system-dirs)
      (str/includes? path "linux-vdso")
      (str/starts-with? (base-name path) "ld-linux")))

(defn soname
  "A shared object's DT_SONAME, or nil."
  [path]
  (some (fn [line]
          (let [[_ name] (re-matches #".*\(SONAME\).*\[(.*)\]" (str/trim line))]
            name))
        (str/split-lines (sh "readelf" "-d" path))))

(defn needs
  "The non-system libraries `lib` loads, as paths."
  [lib]
  (keep (fn [line]
          (let [line (str/trim line)]
            (cond
              (str/includes? line "not found")
              (throw (ex-info (str "unresolved dependency: " line) {:lib lib}))
              (str/includes? line "=>")
              (let [path (-> line (str/split #"=>") second str/trim (str/split #"\s+") first)]
                (when-not (system-lib? path) path))
              :else
              (let [path (first (str/split line #"\s+"))]
                (when (and path (str/starts-with? path "/") (not (system-lib? path)))
                  path)))))
        (str/split-lines (sh "ldd" lib))))

(defn bundle-lib!
  "Copy `src` into lib/ and, recursively, everything it needs. `seen` holds the
   destination file names already written."
  [src seen]
  (let [src (real-path src)
        name (base-name src)
        dest (str libdir "/" name)]
    (when-not (contains? @seen name)
      (swap! seen conj name)
      (sh "cp" "-L" src dest)
      (sh "chmod" "u+w" dest)
      (when (have? "patchelf")
        (sh "patchelf" "--set-rpath" "$ORIGIN" dest))
      (when-let [so (soname src)]
        (let [link (str libdir "/" so)]
          (when (and (not= so name) (not (exists? link)))
            (sh "ln" "-sf" name link)
            (swap! seen conj so))))
      (doseq [dep (needs src)]
        (bundle-lib! dep seen)))))

(defn -main []
  (sh "rm" "-rf" app)
  (.mkdirs (file bindir))
  (.mkdirs (file libdir))
  (.mkdirs (file sharedir "applications"))
  (.mkdirs (file sharedir "doc" "almaz"))
  (log "building the executable")
  (sh "jolt" "build" "-m" "almaz.core" "-o" (str bindir "/" bin-name))
  (sh "rm" "-rf" (str bindir "/" bin-name ".build"))
  (spit (str bindir "/" exe-name) launcher)
  (sh "chmod" "+x" (str bindir "/" exe-name))
  (when (have? "patchelf")
    (sh "patchelf" "--set-rpath" "$ORIGIN/../lib" (str bindir "/" bin-name)))
  (log "bundling libraries")
  (let [seen (atom #{})]
    (doseq [lib (conj own-libs (bui-lib))]
      (when-not (exists? lib) (throw (ex-info (str lib " is missing; run its build.sh") {})))
      (bundle-lib! lib seen))
    (log (count @seen) "libraries in lib/"))
  (spit (str sharedir "/applications/almaz.desktop") desktop-entry)
  (when (exists? "LICENSE")
    (sh "cp" "LICENSE" (str sharedir "/doc/almaz/LICENSE")))
  (let [tar (str "target/Almaz-" version "-linux-" arch ".tar.gz")]
    (sh "rm" "-f" tar)
    (sh "tar" "-czf" tar "-C" "target" (str "Almaz-linux-" arch))
    (log "wrote" app "and" tar)))

(-main)
