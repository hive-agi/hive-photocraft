(ns hive-photocraft.json
  "Portable JSON-lines codec. No host JSON library or ambient I/O. Returns error values."
  (:require [clojure.string :as str]
            #?(:cljs [cljs.reader :as reader]
               :default [clojure.edn :as reader])))

(defn- failure [kind hint] {:error {:kind kind :hint hint}})

(defn- codepoint [c]
  #?(:cljs (.charCodeAt (str c) 0)
     :default (int c)))

(defn- escape-text [s]
  (apply str (map (fn [c] (case c
                           \" "\\\"" \\ "\\\\" \newline "\\n"
                           \return "\\r" \tab "\\t" \backspace "\\b"
                           \formfeed "\\f"
                           (if (< (codepoint c) 32)
                             (let [h "0123456789abcdef" n (codepoint c)]
                               (str "\\u00" (nth h (quot n 16)) (nth h (mod n 16))))
                             (str c)))) s)))

(declare emit-value)
(defn- emit-value [v]
  (cond
    (nil? v) "null"
    (= v true) "true"
    (= v false) "false"
    (string? v) (str "\"" (escape-text v) "\"")
    (keyword? v) (emit-value (name v))
    (integer? v) (str v)
    (number? v) (if (or #?(:cljs (js/isNaN v) :default (Double/isNaN (double v)))
                        #?(:cljs (not (js/isFinite v)) :default (Double/isInfinite (double v))))
                  (throw (ex-info "JSON number must be finite" {})) (str v))
    (map? v) (str "{" (str/join "," (map (fn [[k value]]
                                           (when-not (or (keyword? k) (string? k))
                                             (throw (ex-info "JSON keys must be strings" {})))
                                           (str (emit-value (if (keyword? k) (name k) k)) ":" (emit-value value))) v)) "}")
    (sequential? v) (str "[" (str/join "," (map emit-value v)) "]")
    :else (throw (ex-info "Unsupported JSON value" {}))))

(defn encode
  "Encode one JSON value and terminate with LF. Refuse unsupported data as a value."
  [value]
  (try {:ok (str (emit-value value) "\n")}
       (catch #?(:cljs :default :default Exception) _
         (failure :json/unsupported "Use finite numbers, arrays, objects with string keys, strings, booleans or null."))))

(defn- ws? [c] (contains? #{\space \newline \return \tab} c))
(defn- digit? [c] (and c (<= 48 (codepoint c) 57)))
(defn- hexdigit [c]
  (when c
    (let [n (codepoint c)]
      (cond (<= 48 n 57) (- n 48)
            (<= 65 n 70) (- n 55)
            (<= 97 n 102) (- n 87)))))

(defn- parse-value [s start]
  (let [len (count s)
        skip (fn [i] (loop [j i] (if (and (< j len) (ws? (nth s j))) (recur (inc j)) j)))
        char-at (fn [i] (when (< i len) (nth s i)))]
    (letfn [(string-at [i]
              (loop [j (inc i) out []]
                (when (>= j len) (throw (ex-info "Unterminated string" {})))
                (let [c (char-at j)]
                  (cond
                    (= c \" ) [(apply str out) (inc j)]
                    (= c \\) (let [e (char-at (inc j))]
                                 (when-not e (throw (ex-info "Incomplete escape" {})))
                                 (if (= e \u)
                                   (let [hex (mapv #(hexdigit (char-at %)) (range (+ j 2) (+ j 6)))]
                                     (when (some nil? hex) (throw (ex-info "Invalid unicode escape" {})))
                                     (recur (+ j 6) (conj out #?(:cljs (js/String.fromCharCode (reduce (fn [a n] (+ (* 16 a) n)) 0 hex)) :default (char (reduce (fn [a n] (+ (* 16 a) n)) 0 hex))))))
                                   (let [escaped (case e
                                                   \" \" \\ \\ \/ \/ \b \backspace \f \formfeed
                                                   \n \newline \r \return \t \tab nil)]
                                     (when-not escaped (throw (ex-info "Invalid escape" {})))
                                     (recur (+ j 2) (conj out escaped)))))
                    (< (codepoint c) 32) (throw (ex-info "Control character in string" {}))
                    :else (recur (inc j) (conj out c))))))
            (value-at [i]
              (let [i (skip i) c (char-at i)]
                (cond
                  (= c \" ) (string-at i)
                  (= c \{) (loop [j (skip (inc i)) out {}]
                              (if (= (char-at j) \}) [out (inc j)]
                                  (do (when-not (= (char-at j) \" ) (throw (ex-info "Expected object key" {})))
                                      (let [[k after-key] (string-at j)
                                            colon (skip after-key)]
                                        (when-not (= (char-at colon) \:) (throw (ex-info "Expected colon" {})))
                                        (let [[v after-v] (value-at (inc colon))
                                              sep (skip after-v)]
                                          (cond (= (char-at sep) \}) [(assoc out k v) (inc sep)]
                                                (= (char-at sep) \,) (recur (skip (inc sep)) (assoc out k v))
                                                :else (throw (ex-info "Expected object separator" {}))))))))
                  (= c \[) (loop [j (skip (inc i)) out []]
                              (if (= (char-at j) \]) [out (inc j)]
                                  (let [[v next-i] (value-at j) sep (skip next-i)]
                                    (cond (= (char-at sep) \]) [(conj out v) (inc sep)]
                                          (= (char-at sep) \,) (do (when (= (char-at (skip (inc sep))) \]) (throw (ex-info "Trailing comma" {}))) (recur (skip (inc sep)) (conj out v)))
                                          :else (throw (ex-info "Expected array separator" {}))))))
                  (= c \t) (if (= "true" (subs s i (min len (+ i 4)))) [true (+ i 4)] (throw (ex-info "Invalid literal" {})))
                  (= c \f) (if (= "false" (subs s i (min len (+ i 5)))) [false (+ i 5)] (throw (ex-info "Invalid literal" {})))
                  (= c \n) (if (= "null" (subs s i (min len (+ i 4)))) [nil (+ i 4)] (throw (ex-info "Invalid literal" {})))
                  (or (= c \-) (digit? c))
                  (let [end (loop [j i]
                              (if (contains? #{\- \+ \. \e \E \0 \1 \2 \3 \4 \5 \6 \7 \8 \9} (char-at j))
                                (recur (inc j)) j))
                        token (subs s i end)
                        exponent? (or (str/includes? token "e") (str/includes? token "E"))
                        dot? (str/includes? token ".")
                        source (if (and exponent? (not dot?))
                                 (str/replace (str/replace token "e" ".0e") "E" ".0E") token)]
                    (when (or (str/starts-with? token "+") (str/starts-with? token "0x")
                              (and (> (count token) 1) (= (first token) \0) (digit? (second token))))
                      (throw (ex-info "Invalid JSON number" {})))
                    (let [n (try (reader/read-string source)
                                 (catch #?(:cljs :default :default Exception) _ nil))]
                      (when-not (number? n) (throw (ex-info "Invalid JSON number" {})))
                      [n end]))
                  :else (throw (ex-info "Invalid JSON value" {})))))]
      (value-at start))))

(defn decode
  "Decode exactly one JSON line; reject trailing input and malformed values."
  [line]
  (if (not (string? line))
    (failure :json/invalid "Supply one JSON line as a string.")
    (try (let [[v end] (parse-value line 0)
               rest (subs line end)]
           (if (every? ws? rest) {:ok v}
               (failure :json/trailing "Send one JSON value per line.")))
         (catch #?(:cljs :default :default Exception) _
           (failure :json/invalid "Send a valid JSON value followed by a newline.")))))
