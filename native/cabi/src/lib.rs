//! Photocraft Headless behind the shared Hive JSON C ABI.
use std::ffi::{CStr, CString, c_char};
use std::path::Path;
use std::sync::{Mutex, OnceLock, PoisonError};

use photocraft_automation::{AuthorizedWorkspace, Headless, rpc::METHODS};
use serde_json::{Value, json};

// The method names come from the reference registry. Metadata is checked against it
// before answering ops, so a reference upgrade cannot silently omit a method.
const DESCRIPTIONS: &[(&str, &str, bool)] = &[
    ("engine.execute", "Execute one registered engine command", false),
    ("jobs.list", "List and apply completed background jobs", false),
    ("jobs.cancel", "Cancel a background job", false),
    ("engine.commands", "List engine commands, optionally filtered", true),
    ("session.list", "List open documents and session state", true),
    ("doc.open", "Open a document within the granted read root", false),
    ("doc.new", "Create a new document", false),
    ("doc.save", "Save a document within the granted write root", false),
    ("doc.inspect", "Inspect a document", true),
    ("doc.render", "Render a document to PNG or write it in the granted root", false),
    ("doc.select", "Select the active document", false),
    ("doc.close", "Close a document", false),
    ("batch", "Run bounded engine commands or methods in order", false),
    ("methods", "List reference Headless method names", true),
];

struct Session {
    headless: Headless,
    roots: (Option<String>, Option<String>),
}
static SESSION: OnceLock<Mutex<Option<Session>>> = OnceLock::new();

fn roots(request: &Value) -> Result<(Option<String>, Option<String>), String> {
    let config = request.get("roots");
    if config.is_some_and(|v| !v.is_object()) {
        return Err("roots must be an object with read and write directory paths".into());
    }
    let env = std::env::var("HIVE_PHOTOCRAFT_ROOTS").ok();
    // A single environment directory grants both read and write. Explicit roots
    // override it independently. No configured root means no filesystem authority.
    let root = |key: &str| -> Result<Option<String>, String> {
        let value = config.and_then(|v| v.get(key));
        match value {
            Some(Value::String(s)) if !s.is_empty() => Ok(Some(s.clone())),
            Some(Value::Null) => Ok(None),
            Some(_) => Err(format!("roots.{key} must be a non-empty directory path or null")),
            None => Ok(env.clone()),
        }
    };
    Ok((root("read")?, root("write")?))
}

fn run(op: &str, request: Value) -> Result<Value, String> {
    let request = request.as_object().ok_or("request_json must be a JSON object")?;
    let request = Value::Object(request.clone());
    if let Some(timeout) = request.get("timeout_ms") {
        if timeout.as_u64().filter(|n| *n > 0).is_none() {
            return Err("timeout_ms must be a positive integer (advisory for synchronous calls)".into());
        }
    }
    match op {
        "capabilities" => Ok(json!({"library": "photocraft", "version": env!("CARGO_PKG_VERSION"), "reference": "47f9306fd06d5dee11acb84b108606f4c867222a"})),
        "ops" => {
            if DESCRIPTIONS.len() != METHODS.len() || !DESCRIPTIONS.iter().zip(METHODS).all(|((name, _, _), method)| name == method) {
                return Err("reference Headless methods changed: update C ABI operation metadata".into());
            }
            let mut entries = vec![json!({"name": "ops", "doc": "List C ABI operations", "pure": true}),
                                   json!({"name": "capabilities", "doc": "Describe library version and pinned reference", "pure": true})];
            entries.extend(DESCRIPTIONS.iter().map(|(name, doc, pure)| json!({"name": name, "doc": doc, "pure": pure})));
            Ok(Value::Array(entries))
        }
        method if METHODS.contains(&method) => {
            let chosen = roots(&request)?;
            let mutex = SESSION.get_or_init(|| Mutex::new(None));
            let mut guard = mutex.lock().unwrap_or_else(PoisonError::into_inner);
            if guard.is_none() {
                let workspace = AuthorizedWorkspace::new(chosen.0.as_deref().map(Path::new), chosen.1.as_deref().map(Path::new))
                    .map_err(|e| format!("cannot configure workspace roots: {e}"))?;
                *guard = Some(Session { headless: Headless::with_workspace(workspace), roots: chosen.clone() });
            }
            let session = guard.as_mut().ok_or("session initialization failed")?;
            if request.get("roots").is_some() && session.roots != chosen {
                return Err("workspace roots are fixed after the first Headless call; restart the process to change them".into());
            }
            // roots and timeout_ms belong to the ABI, not the Headless params.
            let mut params = request;
            if let Some(object) = params.as_object_mut() {
                object.remove("roots");
                object.remove("timeout_ms");
            }
            session.headless.handle(method, params).map_err(|e| e.to_string())
        }
        _ => Err(format!("unknown op `{op}`; call `ops` for available operations")),
    }
}

fn error(message: impl AsRef<str>) -> Value {
    json!({"ok": false, "error": message.as_ref()})
}

fn reply(value: Value) -> *mut c_char {
    // Interior NUL cannot occur in a serialized JSON string: serde_json escapes it.
    let encoded = value.to_string();
    match CString::new(encoded) {
        Ok(s) => s.into_raw(),
        Err(_) => CString::new("{\"ok\":false,\"error\":\"cannot encode reply\"}").expect("static JSON has no NUL").into_raw(),
    }
}

/// Invoke an operation. Every returned pointer belongs to the caller and must
/// be released exactly once with hive_free. Input pointers must be valid C strings.
#[unsafe(no_mangle)]
pub extern "C" fn hive_call(op: *const c_char, request_json: *const c_char) -> *mut c_char {
    let result = std::panic::catch_unwind(|| {
        if op.is_null() { return error("op pointer is null; pass an operation name"); }
        if request_json.is_null() { return error("request_json pointer is null; pass a JSON object such as {}"); }
        // SAFETY: the C caller promises valid, NUL-terminated input pointers.
        let op = match unsafe { CStr::from_ptr(op) }.to_str() {
            Ok(s) => s,
            Err(_) => return error("op is not UTF-8; pass a UTF-8 operation name"),
        };
        let json = match unsafe { CStr::from_ptr(request_json) }.to_str() {
            Ok(s) => s,
            Err(_) => return error("request_json is not UTF-8; pass a UTF-8 JSON object"),
        };
        let request = match serde_json::from_str::<Value>(json) {
            Ok(v) => v,
            Err(e) => return error(format!("malformed JSON: {e}; pass a JSON object")),
        };
        match run(op, request) {
            Ok(value) => json!({"ok": true, "value": value}),
            Err(message) => error(message),
        }
    });
    match result {
        Ok(value) => reply(value),
        Err(payload) => {
            let message = payload.downcast_ref::<&str>().copied().map(str::to_owned)
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".into());
            reply(error(format!("internal panic: {message}")))
        }
    }
}

/// Release a pointer returned by hive_call; null is a no-op.
#[unsafe(no_mangle)]
pub extern "C" fn hive_free(ptr: *mut c_char) {
    let _ = std::panic::catch_unwind(|| {
        if !ptr.is_null() {
            // SAFETY: only pointers returned by hive_call may be freed here, once.
            drop(unsafe { CString::from_raw(ptr) });
        }
    });
}
