(ns hive-photocraft.addon-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-photocraft.addon :as pc]
            [hive-photocraft.stub :as stub]
            [hive-test.trifecta :refer [deftrifecta]]
            [clojure.test.check.generators :as gen]))

(defn handler-result [input]
  (let [calls (atom [])
        port (stub/recording (stub/stub {:ok {"id" 9 "ok" true "result" {}}}) calls)
        instance (pc/addon-ctor {:transport port})
        handler (:handler (first (addon/tools instance)))]
    {:reply (handler input) :calls @calls
     :addon-id (addon/addon-id instance)
     :tools (mapv :name (addon/tools instance))
     :healthy (:status (addon/health instance))}))

(deftrifecta mounted-boundary #'handler-result
  {:golden-path "test/golden/addon-handler.edn"
   :cases {:call {"command" "call" "id" 9 "engine_command" "file.new" "params" {"width" 20}}
           :invalid {"command" "call" "id" 9 "engine_command" "file.neew" "params" {}}
           :doctor {"command" "doctor"}}
   :gen (gen/elements [{"command" "call" "id" 9 "engine_command" "file.new" "params" {}}
                       {"command" "call" "id" 9 "engine_command" "file.neew" "params" {}}])
   :pred #(and (= "hive.photocraft" (:addon-id %))
               (= ["photocraft"] (:tools %))
               (= :ok (:healthy %))
               (= (if (:ok (:reply %))
                    (if (get-in % [:reply :ok "ok"]) 1 0) 0)
                  (count (:calls %))))
   :num-tests 40
   :mutations [["skips-handler" (fn [_] {:reply {:error {:kind :tool/unknown :hint "Unknown"}}
                                           :calls [] :addon-id "hive.photocraft"
                                           :tools ["photocraft"] :healthy :ok})]]})

(defn failure-handler [input]
  (let [instance (pc/addon-ctor (if (= "fault" (get input "transport"))
                                  {:transport (stub/fault :port/fault)} {}))
        handler (:handler (first (addon/tools instance)))]
    (handler (dissoc input "transport"))))

(deftrifecta failures-remain-values #'failure-handler
  {:golden-path "test/golden/addon-failures.edn"
   :cases {:unknown {"command" "surprise"}
           :absent {"command" "call" "id" 1 "engine_command" "file.new" "params" {}}
           :params {"command" "call" "id" 1 "engine_command" "file.new" "params" []}
           :fault {"command" "call" "id" 1 "engine_command" "file.new" "params" {} "transport" "fault"}}
   :gen (gen/elements [{"command" "surprise"}
                       {"command" "call" "id" 1 "engine_command" "file.new" "params" {}}])
   :pred #(and (keyword? (get-in % [:error :kind]))
               (string? (get-in % [:error :hint])))
   :num-tests 40
   :mutations [["silence-errors" (fn [_] {:ok nil})]]})
