//! Narrow, typed Tauri commands. Rules:
//! - Keys, seeds and links with keys never cross into the webview: links and secret codes are
//!   copied to the clipboard from Rust; the secret code is revealed only on explicit request.
//! - All HTTP (routing, health check) happens here, never in the webview.
//! - Files are read/written only through native dialogs opened from Rust.

use std::path::PathBuf;
use std::sync::Mutex;

use chrono::Utc;
use event_core::{Lang, Template, TravelMode};
use route_core::{
    GpxLimits, HttpRoutingClient, LatLng, MatchRequest, RouteRequest, RoutingClient, RoutingError,
    parse_gpx,
};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, State};
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_dialog::DialogExt;
use zeroize::Zeroizing;

use crate::core::{
    CmdError, CmdResult, Core, DocView, ImportPreview, KIND_EVENT, KIND_IMPORTED, KIND_ROUTE,
    PublishPreview, PublishTarget, Published, VaultStatus,
};
use crate::editing::{EditOp, JobKind, JobOutput, LegJob, Selection};
use crate::gpx_io::{self, GpxApply, GpxExportManifest, GpxExportRequest, GpxPreview};
use crate::settings::{DEFAULT_API, DEV_API, STYLE_URL, Settings};

pub struct AppState {
    pub core: Mutex<Core>,
    pub db_path: PathBuf,
}

fn lock<'a>(state: &'a State<'_, AppState>) -> CmdResult<std::sync::MutexGuard<'a, Core>> {
    state
        .core
        .lock()
        .map_err(|_| CmdError::new("internal", "state lock poisoned"))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    version: &'static str,
    style_url: &'static str,
    settings: Settings,
    api_presets: Vec<(&'static str, &'static str)>,
    vault: VaultStatus,
    platform: &'static str,
}

#[tauri::command]
pub fn app_info(state: State<'_, AppState>) -> CmdResult<AppInfo> {
    let core = lock(&state)?;
    Ok(AppInfo {
        version: env!("CARGO_PKG_VERSION"),
        style_url: STYLE_URL,
        settings: core.settings.clone(),
        api_presets: vec![("Manotu", DEFAULT_API), ("Dev (WS556)", DEV_API)],
        vault: core.vault_status()?,
        platform: std::env::consts::OS,
    })
}

#[tauri::command]
pub fn settings_set(state: State<'_, AppState>, settings: Settings) -> CmdResult<Settings> {
    let settings = settings.normalized();
    settings
        .validate()
        .map_err(|e| CmdError::new("invalid_settings", e.to_string()))?;
    let mut core = lock(&state)?;
    settings
        .save(&core.settings_path)
        .map_err(|e| CmdError::new("storage", e.to_string()))?;
    core.settings = settings.clone();
    Ok(settings)
}

// -- vault --------------------------------------------------------------------------------------

#[tauri::command]
pub fn vault_status(state: State<'_, AppState>) -> CmdResult<VaultStatus> {
    lock(&state)?.vault_status()
}

/// First run. Without a passphrase the OS key store is used; if it is unavailable the UI gets
/// `keychain_unavailable` and must ask for a passphrase (no plaintext fallback).
#[tauri::command]
pub async fn vault_setup(
    state: State<'_, AppState>,
    passphrase: Option<String>,
) -> CmdResult<VaultStatus> {
    let passphrase = passphrase.map(Zeroizing::new);
    let key = match &passphrase {
        None => Some(
            tauri::async_runtime::spawn_blocking(local_store::keychain::create)
                .await
                .map_err(|e| CmdError::new("internal", e.to_string()))??,
        ),
        Some(_) => None,
    };
    let mut core = lock(&state)?;
    match (key, &passphrase) {
        (Some(k), _) => core.store.initialize_with_key(k)?,
        (None, Some(p)) => core
            .store
            .initialize_with_passphrase(p, local_store::KdfParams::DEFAULT)?,
        (None, None) => unreachable!("one of the two is set"),
    }
    core.vault_status()
}

#[tauri::command]
pub async fn vault_unlock(
    state: State<'_, AppState>,
    passphrase: Option<String>,
) -> CmdResult<usize> {
    let passphrase = passphrase.map(Zeroizing::new);
    let key = match &passphrase {
        None => Some(
            tauri::async_runtime::spawn_blocking(local_store::keychain::load)
                .await
                .map_err(|e| CmdError::new("internal", e.to_string()))??
                .ok_or_else(|| {
                    CmdError::new(
                        "keychain_key_missing",
                        "the key store no longer has this vault's key",
                    )
                })?,
        ),
        Some(_) => None,
    };
    let mut core = lock(&state)?;
    match (key, &passphrase) {
        (Some(k), _) => core.store.unlock_with_key(k)?,
        (None, Some(p)) => core.store.unlock_with_passphrase(p)?,
        (None, None) => unreachable!("one of the two is set"),
    }
    core.after_unlock(Utc::now())
}

#[tauri::command]
pub fn vault_lock(state: State<'_, AppState>) -> CmdResult<()> {
    lock(&state)?.lock();
    Ok(())
}

/// Deletes the database, its key and the settings. `confirm` must be the word the UI asked the
/// user to type.
#[tauri::command]
pub fn delete_all_local_data(state: State<'_, AppState>, confirm: String) -> CmdResult<()> {
    if confirm.trim() != "PADAM" && confirm.trim() != "DELETE" {
        return Err(CmdError::new("not_confirmed", "type the confirmation word"));
    }
    let mut core = lock(&state)?;
    core.lock();
    let fresh = local_store::Store::open_in_memory()?;
    let old = std::mem::replace(&mut core.store, fresh);
    old.destroy()?;
    let _ = local_store::keychain::delete();
    let _ = std::fs::remove_file(&core.settings_path);
    core.settings = Settings::default();
    core.store = local_store::Store::open(&state.db_path)?;
    Ok(())
}

// -- library & documents ------------------------------------------------------------------------

#[tauri::command]
pub fn library_list(state: State<'_, AppState>) -> CmdResult<Vec<crate::assemble::DocSummary>> {
    lock(&state)?.library(Utc::now())
}

#[tauri::command]
pub fn doc_new_event(
    state: State<'_, AppState>,
    template: Template,
    center: LatLng,
) -> CmdResult<DocView> {
    let mut core = lock(&state)?;
    let now = Utc::now();
    let lang: Lang = core.settings.lang;
    let doc = crate::templates::new_event(template, center, lang, now);
    core.open_new(doc, now)
}

#[tauri::command]
pub fn doc_new_route(state: State<'_, AppState>, mode: TravelMode) -> CmdResult<DocView> {
    let mut core = lock(&state)?;
    let now = Utc::now();
    let doc = crate::templates::new_route(mode, core.settings.lang, now);
    let rid = doc.routes[0].id.clone();
    core.open_new(doc, now)?;
    core.select(Some(Selection::Route { route_id: rid }))?;
    core.view(now)
}

fn check_kind(kind: &str) -> CmdResult<&str> {
    match kind {
        KIND_EVENT | KIND_ROUTE | KIND_IMPORTED => Ok(kind),
        _ => Err(CmdError::new("invalid", "unknown kind")),
    }
}

#[tauri::command]
pub fn doc_open(state: State<'_, AppState>, kind: String, id: String) -> CmdResult<DocView> {
    lock(&state)?.open(check_kind(&kind)?, &id, Utc::now())
}

#[tauri::command]
pub fn doc_close(state: State<'_, AppState>) -> CmdResult<()> {
    lock(&state)?.close();
    Ok(())
}

#[tauri::command]
pub fn doc_delete(state: State<'_, AppState>, kind: String, id: String) -> CmdResult<()> {
    lock(&state)?.delete(check_kind(&kind)?, &id)
}

#[tauri::command]
pub fn doc_view(state: State<'_, AppState>) -> CmdResult<DocView> {
    lock(&state)?.view(Utc::now())
}

#[tauri::command]
pub fn doc_edit(state: State<'_, AppState>, op: EditOp) -> CmdResult<DocView> {
    lock(&state)?.edit(op, Utc::now())
}

#[tauri::command]
pub fn doc_select(state: State<'_, AppState>, selection: Option<Selection>) -> CmdResult<()> {
    lock(&state)?.select(selection)
}

#[tauri::command]
pub fn doc_undo(state: State<'_, AppState>) -> CmdResult<DocView> {
    lock(&state)?.undo(Utc::now())
}

#[tauri::command]
pub fn doc_redo(state: State<'_, AppState>) -> CmdResult<DocView> {
    lock(&state)?.redo(Utc::now())
}

#[tauri::command]
pub fn doc_save(state: State<'_, AppState>) -> CmdResult<DocView> {
    lock(&state)?.save(Utc::now())
}

#[tauri::command]
pub fn doc_route_to_event(state: State<'_, AppState>) -> CmdResult<DocView> {
    lock(&state)?.route_to_event(Utc::now())
}

#[tauri::command]
pub fn doc_copy_imported(state: State<'_, AppState>) -> CmdResult<DocView> {
    lock(&state)?.copy_imported(Utc::now())
}

// -- routing (all HTTP from Rust; coordinates and travel mode only) ---------------------------

fn routing_code(e: &RoutingError) -> String {
    match e {
        RoutingError::NoRoute => "no_route".into(),
        RoutingError::Unavailable => "unavailable".into(),
        RoutingError::Transport(_) => "offline".into(),
        RoutingError::Status(s) => format!("http_{s}"),
        RoutingError::InvalidRequest(_) => "invalid_request".into(),
        RoutingError::InvalidResponse(_) => "invalid_response".into(),
    }
}

async fn run_job(client: HttpRoutingClient, job: LegJob) -> (LegJob, Result<JobOutput, String>) {
    let res = match &job.kind {
        JobKind::Route { from, to } => client
            .route(&RouteRequest {
                origin: *from,
                destination: *to,
                waypoints: Vec::new(),
                vehicle: job.mode,
            })
            .await
            .map_err(|e| routing_code(&e))
            .and_then(|routes| {
                routes
                    .into_iter()
                    .next()
                    .map(|r| JobOutput::Routed {
                        points: r.points,
                        distance_m: r.distance_m,
                        duration_s: r.duration_s,
                    })
                    .ok_or_else(|| "no_route".to_string())
            }),
        JobKind::Match { points } => client
            .match_line(&MatchRequest {
                points: points.clone(),
                vehicle: job.mode,
            })
            .await
            .map(|m| JobOutput::Matched {
                points: m.points,
                distance_m: m.distance_m,
                duration_s: m.duration_s,
                unmatched: m
                    .unmatched
                    .iter()
                    .map(|u| [u.from_index, u.to_index])
                    .collect(),
            })
            .map_err(|e| routing_code(&e)),
    };
    (job, res)
}

/// Calculates every pending leg ("BELUM") of the open document.
#[tauri::command]
pub async fn route_compute(state: State<'_, AppState>) -> CmdResult<DocView> {
    let (jobs, base) = {
        let core = lock(&state)?;
        (core.jobs()?, core.settings.api_base_url.clone())
    };
    if jobs.is_empty() {
        return lock(&state)?.view(Utc::now());
    }
    let client =
        HttpRoutingClient::new(base).map_err(|e| CmdError::new("offline", e.to_string()))?;
    let handles: Vec<_> = jobs
        .into_iter()
        .map(|j| tauri::async_runtime::spawn(run_job(client.clone(), j)))
        .collect();
    let mut results = Vec::with_capacity(handles.len());
    for h in handles {
        if let Ok(r) = h.await {
            results.push(r);
        }
    }
    lock(&state)?.apply_jobs(results, Utc::now())
}

/// Online indicator: can the routing server be reached? (A GET of `/readyz`; nothing private.)
#[tauri::command]
pub async fn check_online(state: State<'_, AppState>) -> CmdResult<bool> {
    let url = lock(&state)?.settings.readyz_url();
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(5))
        .build()
        .map_err(|e| CmdError::new("internal", e.to_string()))?;
    Ok(client
        .get(url)
        .send()
        .await
        .is_ok_and(|r| r.status().is_success()))
}

// -- native dialogs -----------------------------------------------------------------------------

async fn save_dialog(app: &AppHandle, name: &str, filter: (&str, &[&str])) -> Option<PathBuf> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    app.dialog()
        .file()
        .set_file_name(name)
        .add_filter(filter.0, filter.1)
        .save_file(move |p| {
            let _ = tx.send(p);
        });
    rx.await.ok().flatten().and_then(|p| p.into_path().ok())
}

async fn open_dialog(app: &AppHandle, filter: (&str, &[&str])) -> Option<PathBuf> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    app.dialog()
        .file()
        .add_filter(filter.0, filter.1)
        .pick_file(move |p| {
            let _ = tx.send(p);
        });
    rx.await.ok().flatten().and_then(|p| p.into_path().ok())
}

fn read_bounded(path: &PathBuf, max: u64) -> CmdResult<Vec<u8>> {
    let meta = std::fs::metadata(path).map_err(|e| CmdError::new("file", e.to_string()))?;
    if meta.len() > max {
        return Err(CmdError::new(
            "file_too_large",
            format!("larger than {max} bytes"),
        ));
    }
    std::fs::read(path).map_err(|e| CmdError::new("file", e.to_string()))
}

fn write_file(path: &PathBuf, bytes: &[u8]) -> CmdResult<()> {
    std::fs::write(path, bytes).map_err(|e| CmdError::new("file", e.to_string()))
}

fn file_name(path: &std::path::Path) -> String {
    path.file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default()
}

// -- publish ------------------------------------------------------------------------------------

#[tauri::command]
pub fn publish_preview(state: State<'_, AppState>, stops_only: bool) -> CmdResult<PublishPreview> {
    lock(&state)?.publish_preview(stops_only, Utc::now())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PublishResult {
    rev: u32,
    /// Link: copied to the clipboard (the link holds the key, so it is not returned).
    copied: bool,
    fragment_len: usize,
    /// File: the chosen file name (no path), or `None` when the dialog was cancelled.
    file_name: Option<String>,
}

#[tauri::command]
pub async fn publish_link(
    app: AppHandle,
    state: State<'_, AppState>,
    stops_only: bool,
) -> CmdResult<PublishResult> {
    let (link, rev) = {
        let mut core = lock(&state)?;
        let out = core.publish(PublishTarget::Link { stops_only }, Utc::now())?;
        let rev = core
            .ws
            .as_ref()
            .and_then(|w| w.doc.published.last())
            .map_or(0, |p| p.rev);
        match out {
            Published::Link(l) => (Zeroizing::new(l), rev),
            Published::File(_) => unreachable!("link requested"),
        }
    };
    let fragment_len = link.split_once('#').map_or(0, |(_, f)| f.len());
    app.clipboard()
        .write_text(link.as_str())
        .map_err(|e| CmdError::new("clipboard", e.to_string()))?;
    Ok(PublishResult {
        rev,
        copied: true,
        fragment_len,
        file_name: None,
    })
}

#[tauri::command]
pub async fn publish_file(app: AppHandle, state: State<'_, AppState>) -> CmdResult<PublishResult> {
    // Neutral file name: nothing about the event in it.
    let Some(path) = save_dialog(
        &app,
        "jemputan.manotu-event",
        ("Manotu event", &["manotu-event"]),
    )
    .await
    else {
        return Ok(PublishResult {
            rev: 0,
            copied: false,
            fragment_len: 0,
            file_name: None,
        });
    };
    let (bytes, rev) = {
        let mut core = lock(&state)?;
        let out = core.publish(PublishTarget::File, Utc::now())?;
        let rev = core
            .ws
            .as_ref()
            .and_then(|w| w.doc.published.last())
            .map_or(0, |p| p.rev);
        match out {
            Published::File(b) => (b, rev),
            Published::Link(_) => unreachable!("file requested"),
        }
    };
    write_file(&path, &bytes)?;
    // Verify what was written before telling the user it is ready.
    let written = read_bounded(&path, event_core::limits::MAX_FILE_BYTES as u64)?;
    event_core::parse_file(&written)?;
    Ok(PublishResult {
        rev,
        copied: false,
        fragment_len: 0,
        file_name: Some(file_name(&path)),
    })
}

#[tauri::command]
pub fn secret_code_copy(app: AppHandle, state: State<'_, AppState>) -> CmdResult<()> {
    let code = lock(&state)?.secret_code(Utc::now())?;
    app.clipboard()
        .write_text(code.as_str())
        .map_err(|e| CmdError::new("clipboard", e.to_string()))
}

/// Shown only when the organizer presses "Tunjuk kod" (the code must be read out or typed into
/// another channel; contract §5.2).
#[tauri::command]
pub fn secret_code_reveal(state: State<'_, AppState>) -> CmdResult<String> {
    Ok(lock(&state)?.secret_code(Utc::now())?.to_string())
}

// -- import -------------------------------------------------------------------------------------

#[tauri::command]
pub fn import_link_preview(state: State<'_, AppState>, link: String) -> CmdResult<ImportPreview> {
    let link = Zeroizing::new(link);
    let opened = event_core::open_link(&link)
        .map_err(|e| CmdError::new(e.code(), "Jemputan ini tidak dapat dibuka / disahkan"))?;
    lock(&state)?.import_preview(opened, Utc::now())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PickedFile {
    file_name: String,
    size: usize,
}

#[tauri::command]
pub async fn import_file_pick(
    app: AppHandle,
    state: State<'_, AppState>,
) -> CmdResult<Option<PickedFile>> {
    let Some(path) = open_dialog(&app, ("Manotu event", &["manotu-event"])).await else {
        return Ok(None);
    };
    let bytes = read_bounded(&path, event_core::limits::MAX_FILE_BYTES as u64)?;
    event_core::parse_file(&bytes)?;
    let size = bytes.len();
    lock(&state)?.pending_file = Some(bytes);
    Ok(Some(PickedFile {
        file_name: file_name(&path),
        size,
    }))
}

/// Accepts both code kinds: the 8-character code of suite-2 files and the 52-character code of
/// older suite-1 files (§5.2). Suite 2 runs Argon2id, so the file is opened off the UI thread.
#[tauri::command]
pub async fn import_file_preview(
    state: State<'_, AppState>,
    secret_code: String,
) -> CmdResult<ImportPreview> {
    let code = Zeroizing::new(secret_code);
    let bytes = lock(&state)?
        .pending_file
        .clone()
        .ok_or_else(|| CmdError::new("no_import", "choose a file first"))?;
    let opened = tauri::async_runtime::spawn_blocking(move || event_core::open_file(&bytes, &code))
        .await
        .map_err(|e| CmdError::new("internal", e.to_string()))?;
    let mut core = lock(&state)?;
    let opened = opened.map_err(|e| {
        let code = e.code();
        let msg = if code == "invalid_secret_code" {
            e.to_string()
        } else {
            "Jemputan ini tidak dapat dibuka / disahkan".into()
        };
        CmdError::new(code, msg)
    })?;
    core.pending_file = None;
    core.import_preview(opened, Utc::now())
}

#[tauri::command]
pub fn import_confirm(state: State<'_, AppState>) -> CmdResult<crate::assemble::DocSummary> {
    lock(&state)?.import_confirm(Utc::now())
}

// -- GPX ----------------------------------------------------------------------------------------

#[tauri::command]
pub async fn gpx_pick(app: AppHandle, state: State<'_, AppState>) -> CmdResult<Option<GpxPreview>> {
    let Some(path) = open_dialog(&app, ("GPX", &["gpx"])).await else {
        return Ok(None);
    };
    let limits = GpxLimits::default();
    let bytes = read_bounded(&path, limits.max_bytes as u64)?;
    let doc = parse_gpx(&bytes, &limits).map_err(|e| CmdError::new("gpx", e.to_string()))?;
    Ok(Some(lock(&state)?.gpx_loaded(doc)))
}

#[tauri::command]
pub fn gpx_apply(state: State<'_, AppState>, options: GpxApply) -> CmdResult<DocView> {
    lock(&state)?.gpx_apply(options, Utc::now())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GpxExportArgs {
    route_id: String,
    request: GpxExportRequest,
}

#[tauri::command]
pub fn gpx_export_manifest(
    state: State<'_, AppState>,
    args: GpxExportArgs,
) -> CmdResult<GpxExportManifest> {
    let core = lock(&state)?;
    let ws = core
        .ws
        .as_ref()
        .ok_or_else(|| CmdError::new("no_document", "no document is open"))?;
    let r = ws
        .doc
        .route(&args.route_id)
        .ok_or_else(|| CmdError::new("not_found", "route"))?;
    Ok(gpx_io::manifest(&ws.doc, r, &args.request))
}

#[tauri::command]
pub async fn gpx_export(
    app: AppHandle,
    state: State<'_, AppState>,
    args: GpxExportArgs,
) -> CmdResult<Option<String>> {
    if !args.request.acknowledged_plaintext {
        return Err(CmdError::new(
            "not_confirmed",
            "GPX is plaintext without expiry; confirm before exporting",
        ));
    }
    let text = {
        let core = lock(&state)?;
        let ws = core
            .ws
            .as_ref()
            .ok_or_else(|| CmdError::new("no_document", "no document is open"))?;
        let r = ws
            .doc
            .route(&args.route_id)
            .ok_or_else(|| CmdError::new("not_found", "route"))?;
        gpx_io::export(&ws.doc, r, &args.request)
            .ok_or_else(|| CmdError::new("not_possible", "the route has no calculated geometry"))?
    };
    let Some(path) = save_dialog(&app, "laluan.gpx", ("GPX", &["gpx"])).await else {
        return Ok(None);
    };
    write_file(&path, text.as_bytes())?;
    Ok(Some(file_name(&path)))
}

// -- recovery -----------------------------------------------------------------------------------

#[tauri::command]
pub async fn recovery_export(
    app: AppHandle,
    state: State<'_, AppState>,
    passphrase: String,
) -> CmdResult<Option<String>> {
    let passphrase = Zeroizing::new(passphrase);
    let payload = lock(&state)?.recovery_payload(Utc::now())?;
    let sealed = tauri::async_runtime::spawn_blocking(move || {
        local_store::seal_recovery(&payload, &passphrase, local_store::KdfParams::DEFAULT)
    })
    .await
    .map_err(|e| CmdError::new("internal", e.to_string()))??;
    let name = format!("pemulihan.{}", local_store::RECOVERY_EXTENSION);
    let Some(path) = save_dialog(
        &app,
        &name,
        ("Manotu recovery", &[local_store::RECOVERY_EXTENSION]),
    )
    .await
    else {
        return Ok(None);
    };
    write_file(&path, &sealed)?;
    Ok(Some(file_name(&path)))
}

#[tauri::command]
pub async fn recovery_import(
    app: AppHandle,
    state: State<'_, AppState>,
    passphrase: String,
) -> CmdResult<Option<usize>> {
    let passphrase = Zeroizing::new(passphrase);
    let Some(path) = open_dialog(
        &app,
        ("Manotu recovery", &[local_store::RECOVERY_EXTENSION]),
    )
    .await
    else {
        return Ok(None);
    };
    let bytes = read_bounded(&path, 64 * 1024 * 1024)?;
    let plain = tauri::async_runtime::spawn_blocking(move || {
        local_store::open_recovery(&bytes, &passphrase)
    })
    .await
    .map_err(|e| CmdError::new("internal", e.to_string()))??;
    Ok(Some(lock(&state)?.recovery_restore(&plain, Utc::now())?))
}
