(ns hive-photocraft.transport
  "Effect port for sending one already-validated PhotoCraft control request.")

(defprotocol ControlTransport
  (send-request [this request]
    "Send a single request and return a reply map; never silently retry an edit."))
