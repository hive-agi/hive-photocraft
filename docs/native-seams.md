# Photocraft native seams (reference 47f9306)

Study in progress. Headless owns an engine Session and incremental writers; its synchronous command_start forwards JSON parameters to Session::execute or Session::start (crates/automation/src/headless.rs:19-45,198-227). The registry stores function pointers, not trait objects (crates/engine/src/commands.rs:11-28).
