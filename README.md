# hive-photocraft

A host-neutral PhotoCraft bridge. This wave provides a source-extracted engine command catalog, a portable control-channel JSON-lines codec with explicit authentication, and one IAddon tool backed by an injected transport port. **No live PhotoCraft transport is installed yet.** MIT, 2026 hive-agi. PhotoCraft upstream is MIT OR Apache-2.0 (its own license remains its own).

## What is available

| Layer | JVM | cljw | cljrs | cljs/node |
|---|---|---|---|---|
| Catalog lookup, validation, auth/request/reply values | yes | yes | yes | yes |
| JSON-lines codec (`hive-photocraft.json`) | yes | yes | yes | yes |
| `photocraft` IAddon tool; catalog/doctor/call | yes | no | no | no |
| Injected `ControlTransport` port; test stub and recorder | yes | no | no | no |
| Live control TCP / Rust engine cdylib | planned | planned | planned | no |

`photocraft` takes `command`: `catalog`, `doctor`, or `call`. `call` takes `id` (integer or string), `engine_command` (catalog id), and `params` (object). Without a configured `:transport` it returns `:transport/unavailable` with the fix. The adapter is not a PhotoCraft CLI process: future cljw uses the program's own control TCP protocol; future cljrs uses a Rust cdylib linking the engine crates in-process. Every connection must send `auth` before requests. The JVM boundary renders unknown tool commands with hive-help; portable code answers errors as values.

## Repository map

- `resources/hive_photocraft/catalog.edn`: generated source-visible commands and control methods.
- `dev/extract_catalog.clj`: reproducible extractor, run from the repo root in the cider session with `(load-file "dev/extract_catalog.clj")` then `(extract-catalog/-main "/path/to/photocraft")`.
- `src/hive_photocraft/{core,json}.cljc`: pure portable value objects, validation, wire encoding.
- `src/hive_photocraft/{catalog,transport,addon}.clj`: JVM resource, effect port, IAddon boundary.
- `test/hive_photocraft/`: focused test fixtures with injected stub, recorder and fault adapter; no `with-redefs`.
- `dev/hive_photocraft/portability.cljc`: shared 96-check four-runtime contract.
- `dev/verify_portability.sh`: cljw/cljrs/cljs gate; JVM leg optional and normally run in the existing cider session.

## Verify

`clojure -J-Xmx2g -M:test` runs kaocha (cold JVM; do not run while the cider JVM is active). During development in `hive-photocraft-core`'s cider nREPL, run `(clojure.test/run-tests 'hive-photocraft.core-test 'hive-photocraft.addon-test)` and `(hive-photocraft.portability/run-checks)` after loading `dev/hive_photocraft/portability.cljc`. `bash dev/verify_portability.sh cljw cljrs cljs` compiles the node leg with hive-cljs/shadow-cljs tooling. All portable namespaces require only clojure.core, clojure.string and clojure.edn/cljs.reader, not JVM-only dependencies.

## Measured and not verified

At reference commit 47f9306, source extraction finds 271 distinct literal/adjustment command IDs and 28 control methods. The upstream registry includes dynamic definitions that source extraction cannot certify as complete; 124 entries retain a source-file pointer but no extracted params doc. The PhotoCraft CLI, desktop app, actual auth handshake, live editing, cdylib and full registry were **not** built or tested here. See [reference surface](docs/reference-surface.md). The portable 96-check contract is run independently on each runtime; it does not claim live PhotoCraft parity.
