// Every app command gets an explicit permission; `capabilities/main.json` grants exactly these
// to the main window and nothing else (no fs/shell/http plugin permissions in the webview).
const COMMANDS: &[&str] = &[
    "app_info",
    "settings_set",
    "vault_status",
    "vault_setup",
    "vault_unlock",
    "vault_lock",
    "delete_all_local_data",
    "library_list",
    "doc_new_event",
    "doc_new_route",
    "doc_open",
    "doc_close",
    "doc_delete",
    "doc_view",
    "doc_edit",
    "doc_select",
    "doc_undo",
    "doc_redo",
    "doc_save",
    "doc_route_to_event",
    "doc_copy_imported",
    "route_compute",
    "check_online",
    "publish_preview",
    "publish_link",
    "publish_file",
    "secret_code_copy",
    "secret_code_reveal",
    "import_link_preview",
    "import_file_pick",
    "import_file_preview",
    "import_confirm",
    "gpx_pick",
    "gpx_apply",
    "gpx_export_manifest",
    "gpx_export",
    "recovery_export",
    "recovery_import",
];

fn main() {
    tauri_build::try_build(
        tauri_build::Attributes::new()
            .app_manifest(tauri_build::AppManifest::new().commands(COMMANDS)),
    )
    .expect("tauri build");
}
