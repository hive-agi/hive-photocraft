(ns hive-photocraft.addon-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-photocraft.addon :as pc]
            [hive-photocraft.stub :as stub]))

(deftest mounted-boundary
  (let [calls (atom [])
        port (stub/recording (stub/stub {:ok {"id" 9 "ok" true "result" {}}}) calls)
        instance (pc/addon-ctor {:transport port})
        handler (:handler (first (addon/tools instance)))]
    (is (= "hive.photocraft" (addon/addon-id instance)))
    (is (= ["photocraft"] (mapv :name (addon/tools instance))))
    (is (= true (:success? (addon/initialize! instance {}))))
    (is (= :ok (:status (addon/health instance))))
    (is (>= (count (get-in (handler {"command" "catalog"}) [:ok :commands])) 200))
    (is (= true (get-in (handler {"command" "doctor"}) [:ok :transport-present?])))
    (is (= {"id" 9 "ok" true "result" {}}
           (:ok (handler {"command" "call" "id" 9
                          "engine_command" "file.new" "params" {"width" 20}}))))
    (is (= 1 (count @calls)))
    (is (= "file.new" (get-in (first @calls) ["params" "command"])))
    (is (= :command/unknown
           (get-in (handler {"command" "call" "id" 10
                             "engine_command" "file.neew" "params" {}}) [:error :kind])))
    (is (= 1 (count @calls)))))

(deftest failures-remain-values
  (let [handler (:handler (first (addon/tools (pc/addon-ctor {}))))]
    (is (= :tool/unknown (get-in (handler {"command" "surprise"}) [:error :kind])))
    (is (= :transport/unavailable
           (get-in (handler {"command" "call" "id" 1
                             "engine_command" "file.new" "params" {}}) [:error :kind])))
    (is (= :request/params
           (get-in (handler {"command" "call" "id" 1
                             "engine_command" "file.new" "params" []}) [:error :kind]))))
  (let [handler (:handler (first (addon/tools (pc/addon-ctor
                                                {:transport (stub/fault :port/fault)}))))]
    (is (= :port/fault
           (get-in (handler {"command" "call" "id" 1
                             "engine_command" "file.new" "params" {}}) [:error :kind])))))
