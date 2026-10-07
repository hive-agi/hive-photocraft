(ns hive-photocraft.catalog
  "JVM boundary for the generated upstream command inventory."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn load-catalog
  "Read the source-extracted catalog from the classpath."
  []
  (edn/read-string (slurp (io/resource "hive_photocraft/catalog.edn"))))
