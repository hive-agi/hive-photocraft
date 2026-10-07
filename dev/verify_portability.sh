#!/usr/bin/env bash
# Four-runtime contract gate. JVM checks use the existing cider REPL during development;
# the standalone JVM leg starts only when explicitly requested for a cold release check.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo"
(( $# )) || set -- cljw cljrs cljs
for host in "$@"; do
  case "$host" in
    jvm) clojure -J-Xmx2g -M:dev -e "(require 'hive-photocraft.portability) (let [r (hive-photocraft.portability/run-checks)] (println \"jvm portability:\" (:passes r) \"checks,\" (count (:failures r)) \"failures\") (when (seq (:failures r)) (throw (ex-info \"Portability divergence\" r))))" ;;
    cljw) command -v cljw >/dev/null || { echo 'Missing cljw: install ClojureWasm and put cljw on PATH' >&2; exit 127; }
          timeout 120 cljw -cp src:dev dev/portability.cljw ;;
    cljrs) binary=${CLJRS:-/home/klein/PP/clojurust/target/debug/cljrs}
           [[ -x "$binary" ]] || { echo "Missing cljrs: build clojurust debug and set CLJRS to its binary" >&2; exit 127; }
           timeout 120 "$binary" run dev/portability.cljrs --src-path src --src-path dev ;;
    cljs) command -v node >/dev/null || { echo 'Missing node: install Node.js' >&2; exit 127; }
          clojure -J-Xmx2g -M:cljs -m shadow.cljs.devtools.cli release portability
          node target/portability.js ;;
    *) echo "Unknown runtime $host; use jvm, cljw, cljrs or cljs" >&2; exit 2 ;;
  esac
done
