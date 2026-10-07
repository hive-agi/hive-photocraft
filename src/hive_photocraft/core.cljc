(ns hive-photocraft.core
  "Pure PhotoCraft command and control-channel vocabulary. Receives catalog values;
   does not read files, open sockets or depend on a host runtime."
  (:require [clojure.string :as str]
            [hive-photocraft.json :as json]))

(defn refuse
  "A portable actionable refusal."
  [kind hint]
  {:error {:kind kind :hint hint}})

(defn command
  "Find a command id in an extracted catalog value. Unknown ids refuse."
  [catalog id]
  (if-let [entry (and (string? id)
                      (some #(when (= id (:id %)) %) (:commands catalog)))]
    {:ok entry}
    (refuse :command/unknown "Choose an id listed by catalog; run photocraft catalog to see available commands.")))

(defn method
  "Find a control method declared by the extracted protocol inventory."
  [catalog id]
  (if (and (string? id) (some #(= id %) (:methods catalog)))
    {:ok id}
    (refuse :method/unknown "Choose a method listed by catalog; run photocraft catalog to see available methods.")))

(defn token-valid?
  "True for exactly 64 ASCII hexadecimal characters (256 bits)."
  [token]
  (and (string? token) (= 64 (count token))
       (every? #(let [n #?(:cljs (.charCodeAt (str %) 0)
                          :default (int %))]
                  (or (<= 48 n 57) (<= 97 n 102) (<= 65 n 70))) token)))

(defn auth
  "Build the first control-channel request. Never log or persist the token."
  [token]
  (if (token-valid? token)
    {:ok {"id" "auth" "method" "auth" "params" {"token" token}}}
    (refuse :auth/invalid "Supply a 64-character hexadecimal control token from the protected token file.")))

(defn request
  "Build a control-channel request. Connection must authenticate separately first."
  [catalog id method-id params]
  (cond
    (not (or (string? id) (and (integer? id) (<= 0 id))))
    (refuse :request/id "Supply a non-negative integer or string request id.")
    (not (map? params))
    (refuse :request/params "Supply a JSON object for params (use {} when empty).")
    (:error (method catalog method-id)) (method catalog method-id)
    :else {:ok {"id" id "method" method-id "params" params}}))

(defn execute
  "Validate and build an engine.execute request. Parameter documentation is
   descriptive rather than a machine schema; the engine enforces each command's
   detailed numeric ranges. Never silently coerce an unknown command."
  [catalog id command-id params]
  (cond
    (:error (command catalog command-id)) (command catalog command-id)
    (not (map? params)) (refuse :request/params "Supply a JSON object for params (use {} when empty).")
    :else (request catalog id "engine.execute" {"command" command-id "params" params})))

(defn frame
  "Encode a validated request map as exactly one JSON line."
  [request-value]
  (if (and (map? request-value) (contains? request-value "id")
           (string? (get request-value "method")) (map? (get request-value "params")))
    (json/encode request-value)
    (refuse :frame/invalid "Build a request with auth, request or execute before encoding.")))

(defn reply
  "Decode one JSON-line response, retaining its wire keys."
  [line]
  (let [{:keys [ok error] :as decoded} (json/decode line)]
    (if error decoded
        (if (and (map? ok) (contains? ok "id") (or (= true (get ok "ok")) (= false (get ok "ok"))))
          {:ok ok}
          (refuse :reply/invalid "Expected a response object with id and boolean ok.")))))
