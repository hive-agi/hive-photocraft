(ns hive-photocraft.contracts-test
  "Source-file universe prevents a missing contract from vanishing from the check."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [malli.core :as m]
            [hive-photocraft.contracts]
            [hive-photocraft.addon]
            [hive-photocraft.catalog]
            [hive-photocraft.transport]))

(defn- source-forms [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (loop [forms []]
      (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}} reader)]
        (if (= ::eof form) forms (recur (conj forms form)))))))

(defn- public-functions [file]
  (let [forms (source-forms file)
        ns-name (second (first forms))]
    (into #{}
          (mapcat (fn [form]
                    (case (first form)
                      defn [(symbol (str ns-name) (str (second form)))]
                      defprotocol (for [member (drop 2 form) :when (seq? member)
                                        :when (symbol? (first member))]
                                    (symbol (str ns-name) (str (first member))))
                      [])))
          (rest forms))))

(deftest public-source-contract-completeness
  (let [files (->> (file-seq (io/file "src"))
                   (filter #(.isFile %))
                   (filter #(or (.endsWith (.getName %) ".clj")
                                (.endsWith (.getName %) ".cljc"))))
        universe (into #{} (mapcat public-functions) files)
        declared (into #{} (for [[ns-name members] (m/function-schemas)
                                 [fn-name _] members
                                 :when (.startsWith (str ns-name) "hive-photocraft.")]
                             (symbol (str ns-name) (str fn-name))))]
    (is (>= (count files) 7) "Read the actual source files, not just registered schemas")
    (is (>= (count universe) 18) "Non-vacuity: expect portable and JVM public functions")
    (is (empty? (remove declared universe))
        (str "Missing Malli contracts: " (vec (remove declared universe))))))
