(ns almaz.test-runner
  "Runs every test namespace with clojure.test. Pure namespaces need nothing;
   the native ones need the shims built (native/*/build.sh) and the models
   exported (README). Exits non-zero on any failure, error or load error."
  (:require [clojure.test :as t]))

(def pure '[almaz.lens-test almaz.tile-test almaz.view-test almaz.strip-test almaz.doc-test almaz.autopilot-test almaz.geom-test almaz.color-test almaz.history-test
            almaz.ep-test almaz.export-test almaz.keys-test almaz.files-test])

(def native '[almaz.segment-test almaz.download-test almaz.pixels-test almaz.raw-test almaz.ort-test almaz.coreml-test
              almaz.engine-test almaz.cli-test almaz.worker-test almaz.drop-test])

(defmethod t/report :error [m]
  (t/with-test-out
    (t/inc-report-counter :error)
    (println "\nERROR in" (t/testing-vars-str m))
    (when (seq t/*testing-contexts*) (println (t/testing-contexts-str)))
    (when-let [e (:actual m)]
      (if (instance? Throwable e)
        (do (println "  ->" (ex-message e))
            (when-let [d (ex-data e)] (prn d)))
        (prn e)))))

(defn -main [& args]
  (let [nss (if (some #{"pure"} args) pure (concat pure native))
        load-errors (count (for [ns nss
                                 :let [e (try (require ns) nil
                                              (catch Throwable e e))]
                                 :when e]
                             (println "ERROR requiring" ns ":" (ex-message e))))
        {:keys [fail error]} (apply t/run-tests (keep find-ns nss))]
    (System/exit (if (or (pos? fail) (pos? error) (pos? load-errors)) 1 0))))
