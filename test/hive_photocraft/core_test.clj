(ns hive-photocraft.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-photocraft.core :as core]
            [hive-photocraft.json :as json]))

(def catalog (edn/read-string (slurp (io/resource "hive_photocraft/catalog.edn"))))
(def token (apply str (repeat 64 "a")))

(deftest golden-core
  (testing "auth is the mandatory first request"
    (is (= "auth" (get-in (core/auth token) [:ok "method"])))
    (is (= :auth/invalid (get-in (core/auth "bad") [:error :kind]))))
  (testing "catalog refuses unknown names rather than approximating"
    (is (>= (count (:commands catalog)) 200))
    (is (= "file.new" (get-in (core/command catalog "file.new") [:ok :id])))
    (is (= :command/unknown (get-in (core/command catalog "file.neew") [:error :kind])))
    (is (= :method/unknown (get-in (core/method catalog "engine.nope") [:error :kind]))))
  (testing "builders and JSON lines"
    (let [request (:ok (core/execute catalog 1 "file.new" {"width" 42}))
          wire (:ok (core/frame request))]
      (is (= "engine.execute" (get request "method")))
      (is (= request (:ok (json/decode wire))))
      (is (= \newline (last wire))))
    (is (= :request/params (get-in (core/request catalog 1 "engine.commands" []) [:error :kind])))
    (is (= :reply/invalid (get-in (core/reply "{}") [:error :kind])))
    (is (= {"id" 1 "ok" false "error" "not enabled"}
           (:ok (core/reply "{\"id\":1,\"ok\":false,\"error\":\"not enabled\"}"))))))

(deftest property-roundtrip
  (is (:pass? (tc/quick-check 120
             (prop/for-all [s gen/string-alphanumeric
                            n (gen/choose -100000 100000)]
               (= {"text" s "n" n "values" [true nil false]}
                  (:ok (json/decode (:ok (json/encode {"text" s "n" n
                                                       "values" [true nil false]}))))))))))

(deftest mutation-guards
  ;; Each representative mutant must be refused instead of silently crossing the port.
  (doseq [bad [nil "x" (apply str (repeat 63 "a")) (apply str (repeat 64 "z"))]]
    (is (= :auth/invalid (get-in (core/auth bad) [:error :kind]))))
  (doseq [bad [nil 1 [] ""]]
    (is (= :request/params (get-in (core/request catalog 1 "engine.commands" bad) [:error :kind]))))
  (doseq [bad ["{" "[1,]" "true false" "{\"ok\":1}"]]
    (is (contains? (core/reply bad) :error))))
