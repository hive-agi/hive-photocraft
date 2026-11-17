(ns hive-photocraft.root-tool-contract-test
  (:require [hive-addon.tool-contract.test :refer [deftest-root-tools]]
            [hive-addon.protocol :as proto]
            [hive-photocraft.addon :as a]))

(deftest-root-tools root-tools (proto/tools (a/addon-ctor {})))
