(ns hive-photocraft.portability-runner
  "Node executable for the shared portable checks."
  (:require [hive-photocraft.portability :as checks]))

(defn -main []
  (let [{:keys [passes failures]} (checks/run-checks)]
    (println "cljs portability:" passes "checks," (count failures) "failures")
    (doseq [failure failures] (println "FAIL" failure))
    (when (seq failures) (.exit js/process 1))))
