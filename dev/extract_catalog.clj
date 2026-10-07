(ns extract-catalog
  "Extract metadata from PhotoCraft's Rust CommandSpec registry and control protocol.
   Usage: clojure -M:dev -m extract-catalog /path/to/photocraft"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- locate [text fragment start]
  (str/index-of text fragment start))

(defn- quoted [text start]
  (when (and (< start (count text)) (= \" (.charAt text start)))
    (when-let [end (locate text "\"" (inc start))]
      [(subs text (inc start) end) (inc end)])))

(defn- skip-space [text start]
  (loop [i start]
    (if (and (< i (count text)) (Character/isWhitespace (.charAt text i)))
      (recur (inc i)) i)))

(defn- fields [text start delimiter]
  (when-let [[id i] (quoted text (skip-space text start))]
    (let [i (skip-space text i)]
      (when (and (< i (count text)) (= delimiter (.charAt text i)))
        (when-let [[label end] (quoted text (skip-space text (inc i)))]
          [id label end])))))

(defn- doc-string [slice]
  (let [raw (locate slice "r##\"{" 0)
        ordinary (locate slice "\"{" 0)
        raw-end (when raw
                  (loop [i (+ raw 5)]
                    (cond
                      (>= i (count slice)) nil
                      (= \# (.charAt slice i)) nil
                      (str/starts-with? (subs slice i) "\"##") (+ i 3)
                      :else (recur (inc i)))))
        ordinary-end (when ordinary (locate slice "\"" (+ ordinary 2)))]
    (if (and raw-end (or (nil? ordinary) (< raw ordinary)))
      (subs slice raw raw-end)
      (when ordinary-end (subs slice ordinary (inc ordinary-end))))))

(defn- entries [file]
  (let [text (slurp file)
        source (.getName file)
        macros (for [macro ["cmd" "filter_cmd" "style_cmd"]
                     :let [marker (str macro "!")]
                     [_ id label end] (loop [from 0 out []]
                                         (if-let [at (locate text marker from)]
                                           (let [open (skip-space text (+ at (count marker)))
                                                 data (when (and (< open (count text))
                                                                 (= \( (.charAt text open)))
                                                        (fields text (inc open) \,))]
                                             (recur (inc at) (if data
                                                               (conj out (into [at] data)) out)))
                                           out))
                     :let [id (if (= macro "style_cmd") (str "layer.layerStyle." id) id)
                           slice (subs text end (min (count text) (+ end 2800)))]]
                 {:id id :label label
                  :params-doc (or (doc-string slice) "See upstream CommandSpec")
                  :source source})
        structs (loop [from 0 out []]
                  (if-let [at (locate text "CommandSpec" from)]
                    (let [open (skip-space text (+ at (count "CommandSpec")))
                          id-at (when (and (< open (count text)) (= \{ (.charAt text open)))
                                  (skip-space text (inc open)))
                          id-at (when (and id-at (str/starts-with? (subs text id-at) "id:"))
                                  (skip-space text (+ id-at 3)))
                          id-pair (when id-at (quoted text id-at))
                          comma (when id-pair (skip-space text (second id-pair)))
                          label-at (when (and comma (< comma (count text))
                                              (= \, (.charAt text comma)))
                                     (skip-space text (inc comma)))
                          label (when (and label-at (str/starts-with? (subs text label-at) "label:"))
                                  (first (quoted text (skip-space text (+ label-at 6)))))]
                      (recur (inc at) (if label
                                        (conj out {:id (first id-pair) :label label
                                                   :params-doc "See upstream CommandSpec" :source source}) out)))
                    out))]
    (concat macros structs)))

(defn- generated-adjustments [root]
  (let [text (slurp (io/file root "crates/engine/src/commands.rs"))
        from (locate text "const ADJ:" 0)
        to (when from (locate text "for &(kind, label, params) in ADJ" from))
        table (subs text (+ from (count "const ADJ:")) to)
        rows (loop [from 0 out []]
               (if-let [open (locate table "(" from)]
                 (let [start (skip-space table (inc open))
                       data (fields table start \,)
                       after (when data (skip-space table (nth data 2)))
                       params (when (and after (< after (count table))
                                         (= \, (.charAt table after)))
                                (let [start (skip-space table (inc after))
                                      raw? (str/starts-with? (subs table start) "r#")
                                      quote-at (if raw? (locate table "\"" start) start)
                                      hashes (when raw? (subs table (inc start) quote-at))
                                      finish (when quote-at
                                               (locate table (str "\"" hashes) (inc quote-at)))
                                      color (when (and raw? quote-at) (locate table "\"#" (inc quote-at)))
                                      color? (and color finish (< color finish))
                                      finish (if color? color finish)]
                                  (when finish (subs table start (+ finish (if color? 2 (+ 1 (count hashes))))))))]
                   (recur (inc open) (if (and data params)
                                       (conj out [(first data) (second data) params]) out)))
                 out))]
    (for [[kind label params] rows
          prefix ["layer.newAdjustmentLayer." "image.adjustments."]]
      {:id (str prefix kind) :label label :params-doc params :source "commands.rs:ADJ"})))

(defn- control-methods [text]
  (->> ["engine." "ui." "app." "jobs."]
       (mapcat (fn [prefix]
                 (loop [from 0 out []]
                   (if-let [at (locate text prefix from)]
                     (let [end (loop [i (+ at (count prefix))]
                                 (if (and (< i (count text))
                                          (let [c (.charAt text i)]
                                            (or (Character/isLetter c) (= c \.))))
                                   (recur (inc i)) i))
                           method (subs text at end)
                           before (when (pos? at) (.charAt text (dec at)))
                           after (when (< end (count text)) (.charAt text end))]
                       (recur (inc at) (if (and (> end (+ at (count prefix)))
                                                (not (str/ends-with? method "."))
                                                (#{\` \"} before) (#{\` \"} after))
                                         (conj out method) out)))
                     out))))
       distinct sort vec))

(defn -main [& [root]]
  (when-not root (throw (ex-info "Pass PhotoCraft source root" {})))
  (let [dir (io/file root "crates/engine/src")
        files (filter #(str/ends-with? (.getName %) ".rs") (file-seq dir))
        commands (->> (concat (mapcat entries files) (generated-adjustments root))
                      (filter #(str/includes? (:id %) "."))
                      (sort-by :id)
                      (reduce (fn [m entry] (update m (:id entry)
                                                    #(if (and % (not= "See upstream CommandSpec" (:params-doc %)))
                                                       % entry))) {})
                      vals (sort-by :id) vec)
        protocol (slurp (io/file root "crates/ui-egui/src/control.rs"))
        methods (control-methods protocol)
        output {:source "photocraft/crates/engine/src (CommandSpec, cmd!, filter_cmd!, style_cmd!, ADJ)"
                :control-source "photocraft/crates/ui-egui/src/control.rs"
                :commands commands :methods methods}]
    (spit "resources/hive_photocraft/catalog.edn"
          (str ";; Generated by dev/extract_catalog.clj; do not edit by hand.\n"
               (pr-str output) "\n"))
    (println "Extracted" (count commands) "commands and" (count methods) "control methods")))
