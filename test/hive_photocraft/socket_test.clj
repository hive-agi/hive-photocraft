(ns hive-photocraft.socket-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-photocraft.addon :as addon]
            [hive-photocraft.transport :as port]
            [hive-photocraft.transport.socket :as socket])
  (:import (java.io BufferedReader InputStreamReader PrintWriter)
           (java.net InetAddress ServerSocket Socket)))

(def ^:private token (apply str (repeat 64 "a")))
(def ^:private request {"id" 7 "method" "engine.commands" "params" {}})

(defn- server [behavior connections]
  (let [listener (ServerSocket. 0 8 (InetAddress/getLoopbackAddress))
        seen (atom [])
        worker (future
                 (dotimes [_ connections]
                   (with-open [client (.accept listener)]
                     (.setSoTimeout client 2000)
                     (let [in (BufferedReader. (InputStreamReader. (.getInputStream client)))
                           out (PrintWriter. (.getOutputStream client) true)
                           auth (json/read-str (.readLine in))]
                       (swap! seen conj (select-keys auth ["id" "method"]))
                       (case behavior
                         :bad (.println out (json/write-str {"id" "auth" "ok" false "error" "denied"}))
                         :timeout (Thread/sleep 750)
                         (do
                           (.println out (json/write-str {"id" "auth" "ok" true}))
                           (let [req (json/read-str (.readLine in))]
                             (swap! seen conj (select-keys req ["id" "method"]))
                             (case behavior
                               :skip (do (.println out (json/write-str {"id" 99 "ok" true}))
                                         (.println out (json/write-str {"id" 7 "ok" true "result" {"passed" true}})))
                               :denied (.println out (json/write-str {"id" 7 "ok" false "error" "unknown method"}))
                               :oversize (.println out (apply str (repeat 300 "x")))
                               :reconnect (when (= 2 (count (filter #(= "auth" (get % "id")) @seen)))
                                            (.println out (json/write-str {"id" 7 "ok" true "result" {"passed" true}})))
                               (.println out (json/write-str {"id" 7 "ok" true "result" {"passed" true}}))))))))))]
    {:listener listener :worker worker :seen seen :port (.getLocalPort listener)}))

(defn probe [behavior]
  (let [connections (if (#{:reconnect :oversize :timeout} behavior) 2 1)
        token-file (java.nio.file.Files/createTempFile "photocraft-test-" ".token" (make-array java.nio.file.attribute.FileAttribute 0))
        {:keys [listener worker seen port]} (server behavior connections)]
    (try
      (java.nio.file.Files/writeString token-file token (make-array java.nio.file.OpenOption 0))
      (let [result (port/send-request
                    (socket/socket-transport {:port port :timeout-ms 500 :reply-limit 128
                                              :token-ref {:scheme :file :path (str token-file)}})
                    request)]
        {:result result :seen @seen})
      (finally
        (.close listener)
        (try (deref worker 2500 nil) (catch java.util.concurrent.ExecutionException _ nil))
        (java.nio.file.Files/deleteIfExists token-file)))))

(deftrifecta socket-exchange-contract #'probe
  {:golden-path "test/golden/socket-exchange.edn"
   :cases {:success :success :bad :bad :skip :skip :oversize :oversize
           :timeout :timeout :reconnect :reconnect :denied :denied}
   :xf (fn [{:keys [result seen]}]
         {:kind (get-in result [:error :kind])
          :success (true? (get-in result [:ok "ok"]))
          :methods (mapv #(get % "method") seen)})
   :gen (gen/elements [:success :bad :skip :oversize :timeout :reconnect :denied])
   :pred (fn [{:keys [result seen]}]
           (and (= "auth" (get (first seen) "method"))
                (or (true? (get-in result [:ok "ok"]))
                    (#{:photocraft/unauthorized :photocraft/timeout :photocraft/unavailable}
                     (get-in result [:error :kind])))))
   :num-tests 20
   :mutations [["no-auth" (fn [_] {:result {:ok {"id" 7 "ok" true}}
                                   :seen [{"method" "engine.commands"}]})]]})

(deftrifecta token-reference-contract socket/token-ref?
  {:golden-path "test/golden/socket-token-ref.edn"
   :cases {:file {:scheme :file :path "/tmp/token"}
           :env {:scheme :env :name "PHOTOCRAFT_CONTROL_TOKEN"}
           :raw {:scheme :raw :token "secret"}
           :missing nil}
   :gen (gen/elements [{:scheme :file :path "/tmp/token"} nil])
   :pred boolean? :num-tests 30
   :mutations [["reject-all" (fn [_] false)]]})

(deftrifecta socket-constructor-contract socket/socket-transport
  {:golden-path "test/golden/socket-constructor.edn"
   :cases {:file {:port 8888 :token-ref {:scheme :file :path "/tmp/token"}}}
   :xf #(select-keys (:config %) [:host :port :connect-ms :timeout-ms :request-limit :reply-limit])
   :gen (gen/fmap #(hash-map :port % :token-ref {:scheme :env :name "PHOTOCRAFT_CONTROL_TOKEN"})
                  (gen/choose 1 65535))
   :pred #(and (satisfies? port/ControlTransport %) (= "127.0.0.1" (get-in % [:config :host])))
   :num-tests 30 :mutations [["ignore-port" (fn [cfg] (socket/->SocketTransport
                                                        (assoc socket/default-config :token-ref (:token-ref cfg))))]]})

(deftest required-installer
  (is (= :photocraft/unauthorized
         (try (addon/addon-ctor {:control-port 1}) nil
              (catch clojure.lang.ExceptionInfo e (:kind (ex-data e))))))
  (is (= :photocraft/unauthorized
         (try (socket/socket-transport {:token-ref {:scheme :raw :token token}}) nil
              (catch clojure.lang.ExceptionInfo e (:kind (ex-data e)))))))

(deftest rejects-non-loopback
  (is (= :photocraft/unavailable
         (get-in (port/send-request (socket/socket-transport
                                     {:host "192.0.2.1" :token-ref {:scheme :env :name "PHOTOCRAFT_CONTROL_TOKEN"}})
                                    request) [:error :kind]))))
