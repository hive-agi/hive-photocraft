(ns hive-photocraft.catalog
  "JVM boundary for the generated upstream command inventory."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [hive-photocraft.schema :as s]))

(defn load-catalog
  "Read the source-extracted catalog from the classpath."
  []
  (edn/read-string (slurp (io/resource "hive_photocraft/catalog.edn"))))

(m/=> load-catalog [:=> [:cat] s/Catalog])
