(ns hive-photocraft.public-trifecta-test
  "Golden, property and independently written mutation facets for all public boundaries."
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-photocraft.core :as core]
            [hive-photocraft.json :as json]
            [hive-photocraft.catalog :as catalog]
            [hive-photocraft.addon :as pc]
            [hive-photocraft.stub :as stub]
            [hive-photocraft.transport :as transport]
            [hive-addon.protocol :as addon]))

(def inventory {:commands [{:id "file.new" :label "New" :params-doc "{}" :source "test"}]
                :methods ["engine.execute" "engine.commands"]})
(def valid-token (apply str (repeat 64 "a")))
(def response {:ok {"id" 9 "ok" true "result" {}}})

(defn handler [config input]
  (let [calls (atom [])
        port (stub/recording (stub/stub response) calls)
        instance (pc/addon-ctor (if (:transport config) {:transport port} {}))
        result ((:handler (first (addon/tools instance))) input)]
    {:result result :calls @calls}))

(deftrifecta refuse-contract #'core/refuse
  {:apply? true :golden-path "test/golden/refuse.edn"
   :cases {:missing [:input/missing "Provide input"] :invalid [:input/invalid "Fix input"]}
   :gen (gen/tuple (gen/elements [:input/missing :input/invalid]) gen/string-alphanumeric)
   :pred #(and (keyword? (get-in % [:error :kind])) (string? (get-in % [:error :hint])))
   :num-tests 60 :mutations [["no-kind" (fn [_ hint] {:error {:hint hint}})]]})

(deftrifecta method-contract #'core/method
  {:apply? true :golden-path "test/golden/method.edn"
   :cases {:known [inventory "engine.execute"] :missing [inventory "missing"]}
   :gen (gen/tuple (gen/return inventory) (gen/elements ["engine.execute" "missing"]))
   :pred #(or (= "engine.execute" (:ok %)) (= :method/unknown (get-in % [:error :kind])))
   :num-tests 60 :mutations [["accept-any" (fn [_ id] {:ok id})]]})

(deftrifecta token-contract #'core/token-valid?
  {:golden-path "test/golden/token.edn"
   :cases {:valid valid-token :short "abc" :nonhex (apply str (repeat 64 "g")) :absent nil}
   :gen gen/string-alphanumeric :pred boolean? :num-tests 60
   :mutations [["accept-any" (fn [_] true)]]})

(deftrifecta request-contract #'core/request
  {:apply? true :golden-path "test/golden/request.edn"
   :cases {:valid [inventory 7 "engine.commands" {}]
           :bad-id [inventory -1 "engine.commands" {}]
           :bad-params [inventory 7 "engine.commands" []]
           :bad-method [inventory 7 "missing" {}]}
   :gen (gen/tuple (gen/return inventory) gen/small-integer
                   (gen/elements ["engine.commands" "missing"]) (gen/return {}))
   :pred #(or (contains? % :ok) (keyword? (get-in % [:error :kind])))
   :num-tests 60 :mutations [["accept-any" (fn [_ id method params] {:ok {"id" id "method" method "params" params}})]]})

(deftrifecta execute-contract #'core/execute
  {:apply? true :golden-path "test/golden/execute.edn"
   :cases {:valid [inventory 9 "file.new" {}]
           :unknown [inventory 9 "wrong" {}]
           :bad-params [inventory 9 "file.new" []]}
   :gen (gen/tuple (gen/return inventory) gen/small-integer
                   (gen/elements ["file.new" "wrong"]) (gen/return {}))
   :pred #(or (contains? % :ok) (keyword? (get-in % [:error :kind])))
   :num-tests 60 :mutations [["ignore-command" (fn [_ id command params] {:ok {"id" id "method" "engine.execute" "params" {"command" command "params" params}}})]]})

(deftrifecta frame-contract #'core/frame
  {:golden-path "test/golden/frame.edn"
   :cases {:valid {"id" 9 "method" "engine.commands" "params" {}}
           :missing-id {"method" "engine.commands" "params" {}}
           :bad-params {"id" 9 "method" "engine.commands" "params" []}}
   :gen (gen/return {"id" 9 "method" "engine.commands" "params" {}})
   :pred #(or (and (string? (:ok %)) (= \newline (last (:ok %))))
              (= :frame/invalid (get-in % [:error :kind])))
   :num-tests 60 :mutations [["encode-any" (fn [value] {:ok (str value "\n")})]]})

(deftrifecta reply-contract #'core/reply
  {:golden-path "test/golden/reply.edn"
   :cases {:ok "{\"id\":9,\"ok\":true}\n" :invalid "{}\n" :garbage "oops"}
   :gen (gen/elements ["{\"id\":9,\"ok\":true}\n" "{}\n" "oops"])
   :pred #(or (contains? % :ok) (keyword? (get-in % [:error :kind])))
   :num-tests 60 :mutations [["always-ok" (fn [line] {:ok line})]]})

(deftrifecta decode-contract #'json/decode
  {:golden-path "test/golden/decode.edn"
   :cases {:object "{\"a\":1}\n" :array "[true,null]\n" :invalid "{oops"}
   :gen (gen/elements ["{\"a\":1}\n" "[true,null]\n" "{oops"])
   :pred #(or (contains? % :ok) (= :json/invalid (get-in % [:error :kind])))
   :num-tests 60 :mutations [["reject-all" (fn [_] {:error {:kind :json/invalid :hint "Send a valid JSON value followed by a newline."}})]]})
