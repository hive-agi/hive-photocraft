(ns hive-photocraft.transport.socket
  "Authenticated, bounded loopback control channel adapter."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hive-photocraft.core :as core]
            [hive-photocraft.transport :as port]
            [malli.core :as m])
  (:import (java.io ByteArrayOutputStream InputStream OutputStream)
           (java.net InetAddress InetSocketAddress Socket SocketTimeoutException)
           (java.nio.charset CodingErrorAction StandardCharsets)
           (java.nio.file Files Paths)))

(def default-config
  "Per-attempt socket bounds and protocol frame ceilings."
  {:host "127.0.0.1" :port 7878 :connect-ms 2000 :timeout-ms 30000
   :request-limit 1048576 :reply-limit 8388608})

(defn token-ref?
  "Whether a configuration has a supported secret reference (not a token value)."
  [ref]
  (and (map? ref)
       (case (:scheme ref)
         :file (and (string? (:path ref)) (not (str/blank? (:path ref))))
         :env (= "PHOTOCRAFT_CONTROL_TOKEN" (:name ref))
         false)))

(defn- failure [kind hint]
  {:error {:kind kind :hint hint}})

(defn- token-at-boundary [ref]
  (let [value (case (:scheme ref)
                :file (str/trim (Files/readString (Paths/get ^String (:path ref) (make-array String 0))
                                             StandardCharsets/UTF_8))
                :env (System/getenv "PHOTOCRAFT_CONTROL_TOKEN"))]
    (when (core/token-valid? value) value)))

(defn- local-address [host]
  (let [address (InetAddress/getByName ^String host)]
    (when (.isLoopbackAddress address) address)))

(defn- read-line! [^InputStream in limit]
  (let [buffer (ByteArrayOutputStream.)]
    (loop []
      (let [byte (.read in)]
        (cond
          (= -1 byte) (throw (ex-info "Connection closed or incomplete reply" {}))
          (= 10 byte) (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                                      (.onMalformedInput CodingErrorAction/REPORT)
                                      (.onUnmappableCharacter CodingErrorAction/REPORT))]
                        (str (.decode decoder (java.nio.ByteBuffer/wrap (.toByteArray buffer)))))
          (>= (.size buffer) (dec limit)) (throw (ex-info "Reply size limit exceeded" {}))
          :else (do (.write buffer byte) (recur)))))))

(defn- write-line! [^OutputStream out value limit]
  (let [bytes (.getBytes (str (json/write-str value) "\n") StandardCharsets/UTF_8)]
    (when (> (alength bytes) limit)
      (throw (ex-info "Request size limit exceeded" {})))
    (.write out bytes)
    (.flush out)))

(defn- matching-reply! [in id limit]
  (loop [skipped 0]
    (when (>= skipped 32)
      (throw (ex-info "Too many unrelated replies" {})))
    (let [reply (json/read-str (read-line! in limit))]
      (if (= id (get reply "id"))
        (if (boolean? (get reply "ok")) reply
            (throw (ex-info "Malformed response" {})))
        (recur (inc skipped))))))

(defn- exchange! [{:keys [host port connect-ms timeout-ms request-limit reply-limit token-ref]} request]
  (with-open [socket (doto (Socket.)
                       (.connect (InetSocketAddress. ^InetAddress (local-address host) (int port)) (int connect-ms))
                       (.setSoTimeout (int timeout-ms)))]
    (let [in (.getInputStream socket)
          out (.getOutputStream socket)
          token (token-at-boundary token-ref)]
      (if-not token
        (failure :photocraft/unauthorized "Control token is absent or invalid; check the secret reference.")
        (do
          (write-line! out (get (core/auth token) :ok) request-limit)
          (let [auth-reply (matching-reply! in "auth" reply-limit)]
            (if-not (true? (get auth-reply "ok"))
              (failure :photocraft/unauthorized "Control channel rejected authentication; check the secret reference.")
              (do
                (write-line! out request request-limit)
                {:ok (matching-reply! in (get request "id") reply-limit)}))))))))

(defn- attempt [config request]
  (try
    (exchange! config request)
    (catch SocketTimeoutException _
      (failure :photocraft/timeout "Control channel timed out; inspect the app and retry after checking state."))
    (catch Exception _
      (failure :photocraft/unavailable "Control channel unavailable or invalid frame; inspect app state before retrying an edit."))))

(defrecord SocketTransport [config]
  port/ControlTransport
  (send-request [_ request]
    (cond
      (not (token-ref? (:token-ref config)))
      (failure :photocraft/unauthorized "A token secret reference is required.")
      (not (try (boolean (local-address (:host config))) (catch Exception _ false)))
      (failure :photocraft/unavailable "Control host must resolve to loopback.")
      :else (let [first-result (attempt config request)]
              (if (and (:error first-result)
                       (not= :photocraft/unauthorized (get-in first-result [:error :kind])))
                (attempt config request)
                first-result)))))

(defn socket-transport
  "Construct a loopback adapter from a port and a required file or environment secret reference."
  [config]
  (let [merged (merge default-config config)]
    (when-not (token-ref? (:token-ref merged))
      (throw (ex-info "A file or environment token reference is required to install live control."
                      {:kind :photocraft/unauthorized})))
    (->SocketTransport merged)))

(m/=> token-ref? [:=> [:cat :any] :boolean])
(m/=> socket-transport [:=> [:cat :map] [:fn #(satisfies? port/ControlTransport %)]])
