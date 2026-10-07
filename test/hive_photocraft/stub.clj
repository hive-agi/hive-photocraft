(ns hive-photocraft.stub
  "Injected test adapters; no global var replacement and no live socket."
  (:require [hive-photocraft.transport :as transport]))

(defrecord StubTransport [answer]
  transport/ControlTransport
  (send-request [_ _] answer))

(defn stub
  "An in-memory adapter answering a fixed reply."
  [answer]
  (->StubTransport answer))

(defrecord RecordingTransport [delegate calls]
  transport/ControlTransport
  (send-request [_ request]
    (swap! calls conj request)
    (transport/send-request delegate request)))

(defn recording
  "Wrap a port, recording each request in a supplied atom."
  [delegate calls]
  (->RecordingTransport delegate calls))

(defrecord FaultTransport [kind]
  transport/ControlTransport
  (send-request [_ _]
    {:error {:kind kind :hint "Injected test failure; inspect the configured transport."}}))

(defn fault
  "Inject a deterministic transport failure."
  [kind]
  (->FaultTransport kind))
