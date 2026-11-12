;; Package Almaz.app for macOS (arm64): `jolt dist`.
;;
;; 1. `jolt build` the app into Contents/MacOS.
;; 2. Copy almaz's native libraries and the BUI kit into Contents/Frameworks,
;;    then every non-system library they load, recursively (Homebrew's ONNX
;;    Runtime alone pulls in ~80). Each copy gets an @rpath install name, its
;;    references are rewritten to @rpath, and it carries @loader_path as an
;;    rpath, so the bundle never touches /opt/homebrew.
;; 3. The bundle's executable is a launcher that puts Contents/Frameworks on
;;    DYLD_LIBRARY_PATH and execs the jolt binary: dyld then finds every library
;;    by its file name there first, which covers both the build-machine paths a
;;    `jolt build` bakes in for :jolt/native candidates and bare names like
;;    glimmer-bui's libbui.dylib. (Signing with hardened runtime for
;;    notarization needs the allow-dyld-environment-variables entitlement.)
;; 4. Write Info.plist, sign every library and then the bundle (ad-hoc, or
;;    the identity in ALMAZ_SIGN_IDENTITY), verify, and zip it.
;;
;; Models aren't bundled: the app downloads them on first use.
(require '[clojure.string :as str]
         '[jolt.process :as p])

(def here (str (.getParent (java.io.File. *file*))))
(def version (:version (clojure.edn/read-string (slurp (str here "/version.edn")))))
(def app "target/Almaz.app")
(def contents (str app "/Contents"))
(def frameworks (str contents "/Frameworks"))
(def exe-name "Almaz")
(def bin-name "almaz-bin")

(def launcher
  "#!/bin/sh
# Almaz.app launcher: load the bundled libraries, then run the app.
here=\"$(cd \"$(dirname \"$0\")\" && pwd)\"
export DYLD_LIBRARY_PATH=\"$here/../Frameworks${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}\"
exec \"$here/almaz-bin\" \"$@\"
")

(defn sh
  "Run a command, returning its stdout; throw with its stderr when it fails."
  [& args]
  (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true} (map str args))]
    (when-not (zero? exit)
      (throw (ex-info (str (str/join " " args) " failed:\n" err) {:exit exit})))
    out))

(defn log [& xs] (apply println "dist:" xs) (flush))

(defn file [& parts] (java.io.File. (str/join "/" parts)))
(defn exists? [path] (.exists (java.io.File. path)))

(def own-libs
  ["native/img/lib/libalmazimg.dylib"
   "native/onnxruntime/lib/libalmazinfer_onnxruntime.dylib"
   "native/raw/lib/libalmazinfer_raw.dylib"
   "native/coreml/lib/libalmazinfer_coreml.dylib"])

(defn bui-lib
  "The BUI kit dylib: $BUI_LIB, or the usual build locations."
  []
  (or (some #(when (and % (exists? %)) %)
            [(System/getenv "BUI_LIB")
             ".deps/bui/lib/libbui.dylib"
             (str (System/getenv "HOME") "/src/bui-src/lib/libbui.dylib")
             (str (System/getenv "HOME") "/src/jlt-commons/glimmer-bui/lib/libbui.dylib")])
      (throw (ex-info "libbui.dylib not found; set BUI_LIB" {}))))

(defn system-lib? [path]
  (or (str/starts-with? path "/usr/lib/") (str/starts-with? path "/System/")))

(defn deps
  "The libraries `lib` loads (not itself, not the system's)."
  [lib]
  (let [[_ & lines] (str/split-lines (sh "otool" "-L" lib))
        id (str/trim (sh "otool" "-D" lib))
        own-id (last (str/split-lines id))]
    (->> lines
         (map #(str/trim (first (str/split % #" \(compatibility"))))
         (remove #(or (system-lib? %) (= % own-id) (str/blank? %))))))

(defn real-path [path] (str/trim (sh "realpath" path)))
(defn base-name [path] (.getName (java.io.File. path)))

(defn resolve-dep
  "A dependency reference as a file to copy. Homebrew references are absolute;
   @rpath / @loader_path ones resolve next to the referring library."
  [ref from]
  (cond
    (str/starts-with? ref "/") ref
    (str/starts-with? ref "@") (str (.getParent (java.io.File. from)) "/" (base-name ref))
    :else ref))

(defn bundle-lib!
  "Copy `src` into Frameworks (once) with its dependencies, relinked to
   @rpath. `seen` is an atom of basenames already bundled."
  [src seen]
  (let [name (base-name src)
        dest (str frameworks "/" name)]
    (when-not (contains? @seen name)
      (swap! seen conj name)
      (sh "cp" (real-path src) dest)
      (sh "chmod" "u+w" dest)
      (sh "install_name_tool" "-id" (str "@rpath/" name) dest)
      (doseq [ref (deps src)
              :let [dep (resolve-dep ref src)]]
        (sh "install_name_tool" "-change" ref (str "@rpath/" (base-name dep)) dest)
        (bundle-lib! dep seen))
      (when-not (str/includes? (sh "otool" "-l" dest) "@loader_path")
        (sh "install_name_tool" "-add_rpath" "@loader_path" dest)))))

(def info-plist
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">
<plist version=\"1.0\">
<dict>
  <key>CFBundleName</key><string>Almaz</string>
  <key>CFBundleDisplayName</key><string>Almaz</string>
  <key>CFBundleIdentifier</key><string>io.github.yogthos.almaz</string>
  <key>CFBundleExecutable</key><string>" exe-name "</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>" version "</string>
  <key>CFBundleVersion</key><string>" version "</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSArchitecturePriority</key><array><string>arm64</string></array>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSHumanReadableCopyright</key><string>GPL-2.0-or-later</string>
</dict>
</plist>
"))

(defn sign! []
  (let [identity (or (System/getenv "ALMAZ_SIGN_IDENTITY") "-")
        libs (map #(str frameworks "/" %) (.list (file frameworks)))]
    (log "signing with" (if (= identity "-") "an ad-hoc signature" identity))
    (doseq [lib (concat libs [(str contents "/MacOS/" bin-name)])]
      (sh "codesign" "--force" "--timestamp=none" "-s" identity lib))
    (sh "codesign" "--force" "--timestamp=none" "-s" identity app)
    (sh "codesign" "--verify" "--deep" "--strict" app)))

(defn -main []
  (sh "rm" "-rf" app)
  (.mkdirs (file contents "MacOS"))
  (.mkdirs (file frameworks))
  (.mkdirs (file contents "Resources"))
  (log "building the executable")
  (sh "jolt" "build" "-m" "almaz.core" "--signable" "-o" (str contents "/MacOS/" bin-name))
  (sh "rm" "-rf" (str contents "/MacOS/" bin-name ".build"))
  (spit (str contents "/MacOS/" exe-name) launcher)
  (sh "chmod" "+x" (str contents "/MacOS/" exe-name))
  (log "bundling libraries")
  (let [seen (atom #{})]
    (doseq [lib (conj own-libs (bui-lib))]
      (when-not (exists? lib) (throw (ex-info (str lib " is missing; run its build.sh") {})))
      (bundle-lib! lib seen))
    (log (count @seen) "libraries in Frameworks"))
  (spit (str contents "/Info.plist") info-plist)
  (sign!)
  (let [zip (str "target/Almaz-" version "-macos-arm64.zip")]
    (sh "rm" "-f" zip)
    (sh "ditto" "-c" "-k" "--keepParent" app zip)
    (log "wrote" app "and" zip)))

(-main)
