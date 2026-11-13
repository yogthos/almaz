;; Package Almaz for the host platform: `jolt release`.
;;
;; macOS -> target/Almaz.app (a proper bundle with Contents/MacOS and
;;          Contents/Frameworks) plus a zip.
;; Linux -> target/Almaz-linux-<arch>/ with bin/, lib/ and share/, plus a
;;          .tar.gz.
;;
;; The platform scripts are separate so each can assume its own tools
;; (install_name_tool/codesign vs ldd/patchelf).
(require '[jolt.process :as p]
         '[clojure.string :as str])

(defn -main []
  (let [os (str/trim (:out (p/sh "uname" "-s")))]
    (p/shell "jolt"
             (case os
               "Darwin" "native/tools/package_macos.clj"
               "Linux"  "native/tools/package_linux.clj"
               (throw (ex-info (str "release: no packager for " os) {:os os}))))))

(-main)
