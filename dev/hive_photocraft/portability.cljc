(ns hive-photocraft.portability
  "Shared portable contract suite, 80+ checks on every supported runtime."
  (:require [hive-photocraft.core :as core]
            [hive-photocraft.json :as json]
            [clojure.string :as str]))

(def catalog {:commands [{:id "file.new" :label "New" :params-doc "{}"}
                         {:id "document.inspect" :label "Inspect" :params-doc "{}"}]
              :methods ["engine.execute" "engine.commands" "ui.inspect"]})

(defn check
  "Return a failure label or nil for one assertion."
  [label expected actual]
  (when-not (= expected actual)
    {:label label :expected expected :actual actual}))

(defn run-checks
  "Return deterministic portable checks as data, without host I/O."
  []
  (let [token (apply str (repeat 64 "a"))
        sample-values [nil true false 0 1 -1 42 3000 "" "plain" "quote\"" "slash\\"
                       "line\n" "tab\t" "á" [] [1 2 3] [true nil false]
                       {} {"a" 1} {"nested" {"array" [1 "x" false]}}]
        json-checks (map-indexed
                      (fn [i v] (check (str "JSON roundtrip " i) v
                                       (:ok (json/decode (:ok (json/encode v))))))
                      sample-values)
        ids (range 65)
        frames (map (fn [id]
                      (let [request (:ok (core/execute catalog id "file.new" {"width" id}))]
                        (check (str "request frame " id)
                               request (:ok (json/decode (:ok (core/frame request))))))) ids)
        rejects [(check "auth valid" true (core/token-valid? token))
                 (check "auth short" :auth/invalid (get-in (core/auth "abc") [:error :kind]))
                 (check "auth nonhex" false (core/token-valid? (apply str (repeat 64 "z"))))
                 (check "method unknown" :method/unknown
                        (get-in (core/method catalog "ui.missing") [:error :kind]))
                 (check "command unknown" :command/unknown
                        (get-in (core/execute catalog 1 "file.missing" {}) [:error :kind]))
                 (check "params wrong type" :request/params
                        (get-in (core/execute catalog 1 "file.new" []) [:error :kind]))
                 (check "frame missing method" :frame/invalid
                        (get-in (core/frame {"id" 1}) [:error :kind]))
                 (check "reply requires id" :reply/invalid
                        (get-in (core/reply "{\"ok\":true}") [:error :kind]))
                 (check "reject trailing JSON" :json/trailing
                        (get-in (json/decode "true false") [:error :kind]))
                 (check "reject malformed JSON" :json/invalid
                        (get-in (json/decode "[1,]") [:error :kind]))]]
    {:passes (+ (count sample-values) (count ids) (count rejects))
     :failures (vec (remove nil? (concat json-checks frames rejects)))}))
