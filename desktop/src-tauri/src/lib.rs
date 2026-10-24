//! Manotu Desktop: Tauri 2 shell around `event-core`, `route-core` and `local-store`.
//!
//! Privacy boundary (AGENTS.md, docs/events.md §1): events, stops, routes, notes, aliases and
//! keys stay on this machine (encrypted at rest) or in files/links the user exports. The only
//! network calls are stateless routing (`store:false`, coordinates + travel mode) and a health
//! check, both from Rust; the webview can reach the tile host only.

pub mod assemble;
pub mod commands;
pub mod core;
pub mod doc;
pub mod editing;
pub mod gpx_io;
pub mod publish;
pub mod settings;
pub mod templates;

#[cfg(test)]
mod tests;

use std::sync::Mutex;

use tauri::Manager;

use crate::commands::AppState;

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .setup(|app| {
            let data_dir = app.path().app_local_data_dir()?;
            let config_dir = app.path().app_config_dir()?;
            let db_path = data_dir.join("manotu-desktop.db");
            let settings_path = config_dir.join("settings.json");
            let settings = settings::Settings::load(&settings_path);
            let store = local_store::Store::open(&db_path)?;
            app.manage(AppState {
                core: Mutex::new(core::Core::new(store, settings, settings_path)),
                db_path,
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::app_info,
            commands::settings_set,
            commands::vault_status,
            commands::vault_setup,
            commands::vault_unlock,
            commands::vault_lock,
            commands::delete_all_local_data,
            commands::library_list,
            commands::doc_new_event,
            commands::doc_new_route,
            commands::doc_open,
            commands::doc_close,
            commands::doc_delete,
            commands::doc_view,
            commands::doc_edit,
            commands::doc_select,
            commands::doc_undo,
            commands::doc_redo,
            commands::doc_save,
            commands::doc_route_to_event,
            commands::doc_copy_imported,
            commands::route_compute,
            commands::check_online,
            commands::publish_preview,
            commands::publish_link,
            commands::publish_file,
            commands::secret_code_copy,
            commands::secret_code_reveal,
            commands::import_link_preview,
            commands::import_file_pick,
            commands::import_file_preview,
            commands::import_confirm,
            commands::gpx_pick,
            commands::gpx_apply,
            commands::gpx_export_manifest,
            commands::gpx_export,
            commands::recovery_export,
            commands::recovery_import,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Manotu Desktop");
}
