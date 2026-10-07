# Photocraft native C ABI

The Rust crate `native/cabi` exposes the GUI-free `photocraft_automation::Headless` through the shared Hive JSON ABI. The reference is the read-only checkout `/home/klein/PP/hive/clones-ref/photocraft`, commit `47f9306fd06d5dee11acb84b108606f4c867222a`. The method registry is `photocraft_automation::rpc::METHODS` in `crates/automation/src/rpc.rs`. The shim verifies its descriptions against that registry before answering `ops`. The 500+ engine commands remain data behind `engine.commands` and `engine.execute`, not separate C operations.

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
| doc.new | Create a new document | no |
| doc.save | Save a document within the granted write root | no |
| doc.inspect | Inspect a document | no |
| doc.render | Render a document to PNG or write it in the granted root | no |
| doc.select | Select the active document | no |
| doc.close | Close a document | no |
| batch | Run bounded engine commands or methods in order | no |
| methods | List reference Headless method names | no |

`capabilities` returns `{ "library":"photocraft", "version":"0.1.0", "reference":"47f9306fd06d5dee11acb84b108606f4c867222a" }`.

## Build and test

```sh
cd native/cabi
CARGO_BUILD_JOBS=4 CARGO_TARGET_DIR=$HOME/.cache/hive-craft-target/photocraft cargo build --lib
CARGO_BUILD_JOBS=4 CARGO_TARGET_DIR=$HOME/.cache/hive-craft-target/photocraft cargo test -- --nocapture
```

The `cargo build --lib` step creates the cdylib before the test's required `dlopen`/`dlsym` smoke. The Rust tests call both exported symbols, check every advertised operation, reject bad input, verify a write grant and traversal refusal through the loaded cdylib, then create a 40×20 document, execute three commands, render a PNG, and verify its signature and IHDR dimensions. Measured on this host (debug, 2026-10-07): **16 operations**, **4 integration tests passing**, end-to-end document flow **35 ms** (a concurrent run measured 113 ms). Artifact: `$HOME/.cache/hive-craft-target/photocraft/debug/libphotocraft.so`, **443,768,504 bytes** (unstripped debug build). The reference crate is an absolute path dependency, so another machine must check out the pinned reference at the same path or adjust the local manifest path before building; this repo does not vendor or modify the reference.

This wave provides only the native library. The hive-spi native port and JVM FFM/cljrs transport belong to the later integration wave; no Clojure adapter is included here.
