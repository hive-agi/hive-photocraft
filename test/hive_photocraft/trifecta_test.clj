(ns hive-photocraft.trifecta-test
  "Golden, property and mutation facets for public portable entry points."
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-photocraft.core :as core]
            [hive-photocraft.json :as json]))

(def sample-catalog {:commands [{:id "file.new" :label "New" :params-doc "{}"}]
                     :methods ["engine.execute" "engine.commands"]})

(deftrifecta auth-contract
  #'core/auth
  {:golden-path "test/golden/auth.edn"
   :cases {:short "bad" :empty "" :missing nil}
   :gen gen/string-alphanumeric
   :pred #(or (contains? % :ok) (= :auth/invalid (get-in % [:error :kind])))
   :num-tests 100
   :mutations [["accept-any" (fn [token] {:ok {"token" token}})]]})

(deftrifecta catalog-contract
  #'core/command
  {:apply? true
   :golden-path "test/golden/command.edn"
   :cases {:known [sample-catalog "file.new"]
           :unknown [sample-catalog "file.missing"]}
   :gen (gen/tuple (gen/return sample-catalog) gen/string-alphanumeric)
   :pred #(or (contains? % :ok) (= :command/unknown (get-in % [:error :kind])))
   :num-tests 100
   :mutations [["always-unknown" (fn [_ _] {:error {:kind :command/unknown
                                                   :hint "Choose an id listed by catalog; run photocraft catalog to see available commands."}})]]})

(deftrifecta json-contract
  #'json/encode
  {:golden-path "test/golden/json.edn"
   :cases {:object {"a" 1} :array [true nil false] :text "hello"}
   :gen gen/string-alphanumeric
   :pred #(and (string? (:ok %)) (= \newline (last (:ok %))))
   :num-tests 100
   :mutations [["no-newline" (fn [v] {:ok (str v)})]]})
