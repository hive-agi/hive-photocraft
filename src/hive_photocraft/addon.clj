(ns hive-photocraft.addon
  "JVM IAddon boundary. Construct with an injected transport; no network side effects
   at initialization. The portable core handles validation, this boundary routes
   effects and gives refusals their user-facing wording."
  (:require [hive-addon.protocol :as addon]
            [hive-photocraft.catalog :as inventory]
            [hive-photocraft.core :as core]
            [hive-photocraft.transport :as transport]
            [hive-help.core :as help]))

(defn catalog
  "List the source-extracted engine commands and control methods."
  [_]
  {:ok (inventory/load-catalog)})

(defn doctor
  "Report this wave's available and missing transport with a concrete fix."
  [config]
  {:ok {:catalog-present? (boolean (inventory/load-catalog))
        :transport-present? (boolean (:transport config))
        :version "0.1.0"
        :hint (if (:transport config)
                "Injected control transport is available."
                "No control transport is configured. Supply :transport implementing ControlTransport; live TCP and native transports are planned for wave 2.")}})

(defn call
  "Validate one engine command then send it through the injected port exactly once."
  [config {:strs [id engine_command params]}]
  (let [prepared (core/execute (inventory/load-catalog) id engine_command params)]
    (cond
      (:error prepared) prepared
      (not (:transport config))
      (core/refuse :transport/unavailable
                   "Supply :transport implementing ControlTransport. A live TCP adapter is planned for wave 2.")
      :else (transport/send-request (:transport config) (:ok prepared)))))

(defn dispatch
  "Dispatch the sole photocraft tool's closed command vocabulary."
  [config {:strs [command] :as params}]
  (case command
    "catalog" (catalog config)
    "doctor" (doctor config)
    "call" (call config params)
    (assoc (core/refuse :tool/unknown
                        "Use photocraft command catalog, doctor or call.")
           :message (help/unknown-command
                     {:tool "photocraft" :command (str command)
                      :valid-commands ["catalog" "doctor" "call"]}))))

(defn tool-defs
  "One consolidated host tool; descriptions are not a live transport promise."
  [config]
  [{:name "photocraft"
    :description "Catalog PhotoCraft commands, diagnose transport, or call an engine command."
    :inputSchema {:type "object"
                  :properties {"command" {:type "string" :enum ["catalog" "doctor" "call"]}
                               "id" {:type ["integer" "string"]}
                               "params" {:type "object"}
                               "engine_command" {:type "string"}}
                  :required ["command"]}
    :handler (fn [input] (dispatch config input))}])

(defrecord PhotoCraftAddon [config]
  addon/IAddon
  (addon-id [_] "hive.photocraft")
  (addon-type [_] :native)
  (capabilities [_] #{:tools :health-reporting})
  (initialize! [_ _] {:success? true :errors [] :metadata {:tools ["photocraft"]}})
  (shutdown! [_] nil)
  (tools [_] (tool-defs config))
  (schema-extensions [_] [])
  (health [_] {:status (if (:transport config) :ok :degraded)
               :details (:ok (doctor config))})
  (excluded-tools [_] #{})
  (hooks [_] {}))

(defn addon-ctor
  "Pure constructor, resolved by the hive-addon manifest."
  [config]
  (->PhotoCraftAddon config))
