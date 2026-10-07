(ns hive-photocraft.boundary-trifecta-test
  "Trifecta the JVM tool handler through an injected and recording transport."
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-photocraft.core :as core]
            [hive-photocraft.catalog :as catalog]
            [hive-photocraft.addon :as pc]
            [hive-photocraft.stub :as stub]
            [hive-photocraft.transport :as transport]
            [hive-addon.protocol :as addon]))

(def response {:ok {"id" 9 "ok" true "result" {}}})

(defn- handler [config input]
  (let [calls (atom [])
        port (stub/recording (stub/stub response) calls)
        instance (pc/addon-ctor (if (:transport config) {:transport port} {}))
        result ((:handler (first (addon/tools instance))) input)]
    {:result result :calls @calls}))

(deftrifecta catalog-load-contract #'catalog/load-catalog
  {:apply? true :golden-path "test/golden/catalog-load.edn"
   :cases {:commands []} :gen (gen/return [])
   :xf (fn [value] {:command-count (count (:commands value))
                    :method-count (count (:methods value))
                    :first-command (:id (first (:commands value)))})
   :pred #(and (>= (count (:commands %)) 200) (>= (count (:methods %)) 20))
   :num-tests 20 :mutations [["empty" (fn [] {:commands [] :methods []})]]})

(deftrifecta addon-catalog-contract #'pc/catalog
  {:golden-path "test/golden/addon-catalog.edn"
   :cases {:default {}} :gen (gen/return {})
   :xf (fn [value] {:command-count (count (get-in value [:ok :commands]))
                    :method-count (count (get-in value [:ok :methods]))})
   :pred #(>= (count (get-in % [:ok :commands])) 200)
   :num-tests 20 :mutations [["empty" (fn [_] {:ok {:commands [] :methods []}})]]})

(deftrifecta addon-doctor-contract #'pc/doctor
  {:golden-path "test/golden/addon-doctor.edn"
   :cases {:none {} :configured {:transport :stub}}
   :gen (gen/elements [{} {:transport :stub}])
   :pred #(true? (get-in % [:ok :catalog-present?]))
   :num-tests 20 :mutations [["missing-catalog" (fn [_] {:ok {:catalog-present? false}})]]})

(deftrifecta addon-call-contract #'pc/call
  {:apply? true :golden-path "test/golden/addon-call.edn"
   :cases {:no-port [{} {"id" 9 "engine_command" "file.new" "params" {}}]
           :bad-command [{} {"id" 9 "engine_command" "missing" "params" {}}]}
   :gen (gen/tuple (gen/return {}) (gen/elements [{"id" 9 "engine_command" "file.new" "params" {}}
                                                  {"id" 9 "engine_command" "missing" "params" {}}]))
   :pred #(keyword? (get-in % [:error :kind]))
   :num-tests 20 :mutations [["always-unavailable" (fn [_ _] (core/refuse :transport/unavailable "Supply :transport implementing ControlTransport. A live TCP adapter is planned for wave 2."))]]})

(deftrifecta addon-dispatch-contract #'pc/dispatch
  {:apply? true :golden-path "test/golden/addon-dispatch.edn"
   :cases {:doctor [{} {"command" "doctor"}]
           :unknown [{} {"command" "surprise"}]}
   :gen (gen/tuple (gen/return {}) (gen/elements [{"command" "doctor"} {"command" "surprise"}]))
   :pred #(or (contains? % :ok) (keyword? (get-in % [:error :kind])))
   :num-tests 20 :mutations [["unknown-only" (fn [_ _] {:error {:kind :tool/unknown :hint "Use photocraft command catalog, doctor or call."}})]]})

(deftrifecta addon-tools-contract #'pc/tool-defs
  {:golden-path "test/golden/addon-tools.edn"
   :cases {:default {}}
   :xf (fn [tools] (mapv #(select-keys % [:name :description :inputSchema]) tools))
   :gen (gen/return {})
   :pred #(and (= 1 (count %)) (= "photocraft" (:name (first %)))
               (fn? (:handler (first %))))
   :num-tests 20 :mutations [["no-tools" (fn [_] [])]]})

(deftrifecta addon-constructor-contract #'pc/addon-ctor
  {:golden-path "test/golden/addon-constructor.edn"
   :cases {:default {}}
   :xf addon/addon-id
   :gen (gen/return {})
   :pred #(= "hive.photocraft" (addon/addon-id %))
   :num-tests 20 :mutations [["nil-constructor" (fn [_] nil)]]})

(deftrifecta transport-send-contract #'transport/send-request
  {:apply? true :golden-path "test/golden/transport-send.edn"
   :cases {:stub [(stub/stub response) {"id" 9 "method" "engine.commands" "params" {}}]}
   :gen (gen/tuple (gen/return (stub/stub response))
                   (gen/return {"id" 9 "method" "engine.commands" "params" {}}))
   :pred #(= response %)
   :num-tests 20 :mutations [["drops-reply" (fn [_ _] {:error {:kind :transport/lost :hint "Reply lost"}})]]})

(deftrifecta handler-contract #'handler
  {:apply? true :golden-path "test/golden/handler.edn"
   :cases {:call [{:transport true} {"command" "call" "id" 9 "engine_command" "file.new" "params" {}}]
           :unknown [{:transport true} {"command" "call" "id" 9 "engine_command" "missing" "params" {}}]
           :absent [{} {"command" "call" "id" 9 "engine_command" "file.new" "params" {}}]}
   :gen (gen/tuple (gen/return {:transport true})
                   (gen/elements [{"command" "call" "id" 9 "engine_command" "file.new" "params" {}}
                                  {"command" "call" "id" 9 "engine_command" "missing" "params" {}}]))
   :pred #(and (map? (:result %))
               (= (if (:ok (:result %)) 1 0) (count (:calls %))))
   :num-tests 40 :mutations [["drop-recording" (fn [_ _] {:result response :calls []})]]})
