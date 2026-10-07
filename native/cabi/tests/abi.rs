use std::ffi::{CStr, CString};
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

fn session_test_lock() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(())).lock().unwrap()
}
use base64::Engine as _;
use serde_json::{Value, json};

fn call(op: &str, input: Value) -> Value {
    let op = CString::new(op).unwrap();
    let input = CString::new(input.to_string()).unwrap();
    let raw = photocraft::hive_call(op.as_ptr(), input.as_ptr());
    assert!(!raw.is_null());
    // SAFETY: hive_call returned a valid NUL-terminated string until hive_free.
    let response = unsafe { CStr::from_ptr(raw) }.to_str().unwrap().to_owned();
    photocraft::hive_free(raw);
    serde_json::from_str(&response).unwrap()
}

#[test]
fn metadata_and_every_reference_method() {
    let _serial = session_test_lock();
    let ops = call("ops", json!({}));
    let entries = ops["value"].as_array().unwrap();
    assert!(entries.len() >= 16);
    for row in entries {
        let name = row["name"].as_str().unwrap();
        assert!(!row["doc"].as_str().unwrap().is_empty());
        assert!(row["pure"].is_boolean());
        let response = call(name, json!({}));
        assert!(
            !response["error"]
                .as_str()
                .unwrap_or("")
                .contains("unknown op"),
            "{name}: {response}"
        );
    }
    let caps = call("capabilities", json!({}));
    assert_eq!(caps["value"]["library"], "photocraft");
    assert_eq!(
        caps["value"]["reference"],
        "47f9306fd06d5dee11acb84b108606f4c867222a"
    );
}

#[test]
fn invalid_inputs_are_error_envelopes() {
    assert!(
        call("absent", json!({}))["error"]
            .as_str()
            .unwrap()
            .contains("unknown op")
    );
    let name = CString::new("ops").unwrap();
    let bad = CString::new("{").unwrap();
    let raw = photocraft::hive_call(name.as_ptr(), bad.as_ptr());
    assert!(
        unsafe { CStr::from_ptr(raw) }
            .to_str()
            .unwrap()
            .contains("malformed JSON")
    );
    photocraft::hive_free(raw);
    let raw = photocraft::hive_call(std::ptr::null(), bad.as_ptr());
    assert!(
        unsafe { CStr::from_ptr(raw) }
            .to_str()
            .unwrap()
            .contains("op pointer is null")
    );
    photocraft::hive_free(raw);
    let raw = photocraft::hive_call(name.as_ptr(), std::ptr::null());
    assert!(
        unsafe { CStr::from_ptr(raw) }
            .to_str()
            .unwrap()
            .contains("request_json pointer is null")
    );
    photocraft::hive_free(raw);
    let non_utf8 = [0xffu8, 0];
    let raw = photocraft::hive_call(name.as_ptr(), non_utf8.as_ptr().cast());
    assert!(
        unsafe { CStr::from_ptr(raw) }
            .to_str()
            .unwrap()
            .contains("not UTF-8")
    );
    photocraft::hive_free(raw);
    assert_eq!(call("ops", json!([]))["ok"], false);
    photocraft::hive_free(std::ptr::null_mut());
}

#[test]
fn document_flow() {
    let _serial = session_test_lock();
    let started = Instant::now();
    // Metadata can initialize the singleton first. No roots are needed for base64 render.
    let new = call(
        "doc.new",
        json!({"width": 40, "height": 20, "background": "white"}),
    );
    assert_eq!(new["ok"], true, "{new}");
    for (command, params) in [
        ("layer.new.layer", json!({"name": "Ink"})),
        ("edit.fill", json!({"color": "#808080"})),
        ("layer.duplicate", json!({})),
    ] {
        let result = call(
            "engine.execute",
            json!({"command": command, "params": params}),
        );
        assert_eq!(result["ok"], true, "{command}: {result}");
    }
    let render = call("doc.render", json!({"maxSide": 40}));
    assert_eq!(render["ok"], true, "{render}");
    let png = base64::engine::general_purpose::STANDARD
        .decode(render["value"]["base64"].as_str().unwrap())
        .unwrap();
    assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
    assert_eq!(u32::from_be_bytes(png[16..20].try_into().unwrap()), 40);
    assert_eq!(u32::from_be_bytes(png[20..24].try_into().unwrap()), 20);
    println!("end-to-end elapsed: {:?}", started.elapsed());
}

#[cfg(unix)]
#[test]
fn exported_symbols_resolve_with_dlopen() {
    let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join(std::env::var("CARGO_TARGET_DIR").unwrap_or_else(|_| "target".into()))
        .join("debug/libphotocraft.so");
    // Build with `cargo build --lib` before `cargo test`; never silently skip this gate.
    assert!(
        path.exists(),
        "cdylib not built: {}; run cargo build --lib first",
        path.display()
    );
    // SAFETY: tested symbols have the declared ABI, library remains loaded through the call.
    unsafe {
        let library = libloading::Library::new(path).unwrap();
        let invoke: libloading::Symbol<unsafe extern "C" fn(*const i8, *const i8) -> *mut i8> =
            library.get(b"hive_call").unwrap();
        let free: libloading::Symbol<unsafe extern "C" fn(*mut i8)> =
            library.get(b"hive_free").unwrap();
        let invoke_json = |op: &str, input: Value| -> Value {
            let op = CString::new(op).unwrap();
            let request = CString::new(input.to_string()).unwrap();
            let raw = invoke(op.as_ptr(), request.as_ptr());
            let response: Value =
                serde_json::from_str(CStr::from_ptr(raw).to_str().unwrap()).unwrap();
            free(raw);
            response
        };
        assert_eq!(
            invoke_json("capabilities", json!({}))["value"]["library"],
            "photocraft"
        );
        let root =
            std::env::temp_dir().join(format!("photocraft-cabi-root-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let grant = json!({"roots": {"read": root, "write": root}, "width": 12, "height": 8});
        assert_eq!(invoke_json("doc.new", grant)["ok"], true);
        assert_eq!(
            invoke_json("doc.render", json!({"path": "safe.png"}))["ok"],
            true
        );
        assert_eq!(
            &std::fs::read(root.join("safe.png")).unwrap()[..8],
            b"\x89PNG\r\n\x1a\n"
        );
        let rejected = invoke_json("doc.render", json!({"path": "../escape.png"}));
        assert_eq!(rejected["ok"], false, "{rejected}");
        assert_eq!(
            invoke_json("doc.open", json!({"path": "/etc/passwd"}))["ok"],
            false
        );
        let regrant = invoke_json("doc.render", json!({"roots": {"read": "/", "write": "/"}}));
        assert!(
            regrant["error"].as_str().unwrap().contains("fixed"),
            "{regrant}"
        );
        std::fs::remove_dir_all(root).unwrap();
    }
}
