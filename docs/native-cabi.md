# Photocraft native C ABI

The Rust crate `native/cabi` exposes the GUI-free `photocraft_automation::Headless` through the shared Hive JSON ABI. The reference is `storytold/photocraft` commit `01b79958a7b73c3d840b76492069e9635f9f7394`, pinned as a git dependency in `native/cabi/Cargo.toml`. The method registry is `photocraft_automation::rpc::METHODS` in `crates/automation/src/rpc.rs`. The shim verifies its descriptions against that registry before answering `ops`. The 500+ engine commands remain data behind `engine.commands` and `engine.execute`, not separate C operations.

```c
char *hive_call(const char *op, const char *request_json);
void hive_free(char *reply);
```

Pass a UTF-8, NUL-terminated JSON object as `request_json`. The returned C string must be freed **exactly once** with `hive_free`, never libc `free`. Null input pointers, invalid UTF-8, malformed or non-object JSON, and unknown operations return an error envelope. All replies are exactly `{"ok":true,"value":...}` or `{"ok":false,"error":"..."}`. Both C entry points catch Rust panics; a panic in `hive_call` is reported as an `internal panic` error. A caller must still supply *valid* terminated C strings and only free a pointer returned by this library; arbitrary invalid pointers/double frees cannot be made safe by an ABI. No known reference Headless input triggers a panic, so the panic handler has no synthetic production trigger.

The first Headless call lazily creates one session per process. Calls are serialized behind a mutex (including the reference's background-job synchronization). `roots: {"read":"/existing/root", "write":"/existing/root"}` on the first Headless request grants filesystem access through `AuthorizedWorkspace`; omitted directions fall back to `HIVE_PHOTOCRAFT_ROOTS` (one directory granting both read/write), or deny access if unset. Pass explicit `null` to deny a direction even when the environment variable is set. Roots are fixed for the life of the session; a subsequent explicit change is refused. Request file paths must be relative to the granted root, with traversal/absolute paths rejected. **Never uses `trusted_local`**. `timeout_ms` must be a positive integer when present; it is **advisory only** because the synchronous reference engine call cannot be interrupted. The wrapper strips `roots` and `timeout_ms` before delegating params to Headless.

## Operations (measured from reference registry)

`pure` is false for every Headless method: even a read may apply completed jobs during `sync_jobs`, which mutates the document. `ops` and `capabilities` do not touch the session or filesystem.

| Operation | Documentation | Pure |
|---|---|---|
| ops | List C ABI operations | yes |
| capabilities | Describe library version and pinned reference | yes |
| engine.execute | Execute one registered engine command | no |
| jobs.list | List and apply completed background jobs | no |
| jobs.cancel | Cancel a background job | no |
| engine.commands | List engine commands, optionally filtered | no |
| session.list | List open documents and session state | no |
| doc.open | Open a document within the granted read root | no |
| doc.place | Place an image from the granted read root as a layer | no |
| doc.replaceSmartObject | Replace smart object contents from the granted read root | no |
| doc.new | Create a new document | no |
| doc.save | Save a document within the granted write root | no |
| doc.exportLayers | Export layers as files into the granted write root | no |
| doc.exportDataSets | Export variable data sets into the granted write root | no |
| doc.importDataSets | Import variable data sets from a CSV in the granted read root | no |
| doc.inspect | Inspect a document | no |
| doc.render | Render a document to PNG or write it in the granted root | no |
| doc.select | Select the active document | no |
| doc.close | Close a document | no |
| batch | Run bounded engine commands or methods in order | no |
| methods | List reference Headless method names | no |

`capabilities` returns `{ "library":"photocraft", "version":"0.1.0", "reference":"01b79958a7b73c3d840b76492069e9635f9f7394" }`.

## Build and test

```sh
cd native/cabi
CARGO_BUILD_JOBS=4 CARGO_TARGET_DIR=$HOME/.cache/hive-craft-target/photocraft cargo build --lib
CARGO_BUILD_JOBS=4 CARGO_TARGET_DIR=$HOME/.cache/hive-craft-target/photocraft cargo test -- --nocapture
```

The `cargo build --lib` step creates the cdylib before the test's required `dlopen`/`dlsym` smoke. The Rust tests call both exported symbols, check every advertised operation, reject bad input, verify a write grant and traversal refusal through the loaded cdylib, then create a 40×20 document, execute three commands, render a PNG, and verify its signature and IHDR dimensions. Measured on this host (debug, 2026-11-17, reference 01b7995): **21 operations**, **4 integration tests passing**, end-to-end document flow **29 ms**. Artifact: `<CARGO_TARGET_DIR>/debug/libphotocraft.so`, **309,836,384 bytes** (debug build with `CARGO_PROFILE_DEV_DEBUG=0`). (2026-10-07 at 47f9306: 16 operations, 35 ms, 443,768,504 bytes with debug info.)

Upstream's workspace `[patch.crates-io]` table (storytold egui/winit forks and the craft-libs Hayro trees) does not propagate to a downstream crate, so `native/cabi/Cargo.toml` mirrors it verbatim and `Cargo.lock` is seeded from upstream's lock. Re-sync both on every rev bump. To build from a local clone instead of GitHub, set `CARGO_NET_GIT_FETCH_WITH_CLI=true` with a git `url.file:///path/to/photocraft.insteadOf=https://github.com/storytold/photocraft.git` override; this repo does not vendor or modify the reference.

This wave provides only the native library. The hive-spi native port and JVM FFM/cljrs transport belong to the later integration wave; no Clojure adapter is included here.
