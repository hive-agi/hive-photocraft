(ns hive-photocraft.transport
  "Effect port for sending one already-validated PhotoCraft control request."
  (:require [malli.core :as m]
            [hive-photocraft.schema :as s]))

(defprotocol ControlTransport
  (send-request [this request]
    "Send a single request and return a reply map; never silently retry an edit."))

(m/=> send-request [:=> [:cat any? s/Request] map?])
