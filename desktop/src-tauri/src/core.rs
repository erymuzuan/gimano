//! Application core behind the Tauri commands: the encrypted store, the open document with its
//! history, pending imports and publishing. No Tauri, dialog or network code here, so all of it
//! is unit-testable; `commands.rs` adds the platform glue.

use std::path::PathBuf;

use chrono::{DateTime, Utc};
use event_core::{
    ImportCandidate, ImportDecision, OpenedPack, ParentCheck, b64u, decide_import, new_event_id,
    revision_hash_b64u,
};
use local_store::{KeyMode, Store, StoreError, VaultState};
use route_core::GpxDocument;
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use crate::assemble::{
    Change, DocSummary, Issue, RouteSummary, Severity, build_event, route_summary, summarize,
};
use crate::doc::{Doc, DocKind};
use crate::editing::{self, EditError, EditOp, History, JobOutput, LegJob, Selection};
use crate::gpx_io::{self, GpxApply, GpxPreview};
use crate::publish::{self, EventSecrets, ImportRecord, ImportedRev, Prepared};
use crate::settings::Settings;

pub const KIND_EVENT: &str = "event";
pub const KIND_ROUTE: &str = "route";
pub const KIND_IMPORTED: &str = "imported";
pub const KIND_SECRET: &str = "secret";

/// Error shape for every command: a stable `code` for the UI plus an English message.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct CmdError {
    pub code: String,
    pub message: String,
}

impl CmdError {
    pub fn new(code: &str, message: impl Into<String>) -> Self {
        CmdError {
            code: code.into(),
            message: message.into(),
        }
    }
}

impl From<StoreError> for CmdError {
    fn from(e: StoreError) -> Self {
        let code = match &e {
            StoreError::Locked => "locked",
            StoreError::WrongKey => "wrong_key",
            StoreError::AlreadyInitialized => "already_initialized",
            StoreError::NotInitialized => "not_initialized",
            StoreError::WrongMode => "wrong_mode",
            StoreError::WeakPassphrase => "weak_passphrase",
            StoreError::MalformedRecovery => "malformed_recovery",
            StoreError::KeychainUnavailable(_) => "keychain_unavailable",
            StoreError::Kdf | StoreError::Random => "crypto",
            StoreError::Db(_) | StoreError::Io(_) => "storage",
        };
        CmdError::new(code, e.to_string())
    }
}

impl From<EditError> for CmdError {
    fn from(e: EditError) -> Self {
        let code = match e {
            EditError::NotFound(_) => "not_found",
            EditError::NotPossible(_) => "not_possible",
            EditError::Invalid(_) => "invalid",
            EditError::ReadOnly => "read_only",
        };
        CmdError::new(code, e.to_string())
    }
}

impl From<event_core::Error> for CmdError {
    fn from(e: event_core::Error) -> Self {
        CmdError::new(e.code(), e.to_string())
    }
}

impl From<serde_json::Error> for CmdError {
    fn from(e: serde_json::Error) -> Self {
        CmdError::new("storage", format!("stored data is unreadable: {e}"))
    }
}

pub type CmdResult<T> = Result<T, CmdError>;

pub struct Workspace {
    pub doc: Doc,
    pub history: History,
    pub saved_at: Option<DateTime<Utc>>,
    pub selection: Option<Selection>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VaultStatus {
    /// uninitialized | locked | unlocked
    pub state: &'static str,
    /// keychain | passphrase
    pub mode: Option<&'static str>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DocView {
    pub doc: Doc,
    pub routes: Vec<RouteSummary>,
    pub issues: Vec<Issue>,
    pub can_undo: bool,
    pub can_redo: bool,
    pub saved_at: Option<DateTime<Utc>>,
    pub readonly: bool,
    pub selection: Option<Selection>,
    pub pending_legs: usize,
    pub next_rev: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PublishPreview {
    pub ok: bool,
    pub issues: Vec<Issue>,
    /// The exact event JSON that goes into the link (what guests see): the stops-only variant
    /// when `stops_only` was asked for.
    pub event: Option<serde_json::Value>,
    pub rev: u32,
    pub reused: bool,
    pub changes: Vec<Change>,
    pub previous_rev: Option<u32>,
    /// Link length for what was asked for (full, or stops-only).
    pub fragment_len: usize,
    pub fragment_max: usize,
    /// Link length of the full event and of its stops-only variant (§5.5; `None` without
    /// routes), so the UI can offer "Pautan tanpa laluan" only when the full link is too long.
    pub full_fragment_len: usize,
    pub stops_only_fragment_len: Option<usize>,
    /// The file always carries the full event.
    pub file_bytes: usize,
    pub stops_only: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportPreview {
    /// new | already | upgraded | older | fork | newer | different_organizer | expired.
    /// `upgraded`: the full event of a stored stops-only variant; it already replaced the variant
    /// ("laluan penuh ditambah", §7.4) and nothing is left to confirm.
    pub decision: &'static str,
    /// The pack is a stops-only variant (no route lines; the full plan is in the event file).
    pub stops_only: bool,
    pub title: String,
    pub alias: Option<String>,
    pub rev: u32,
    pub safety_code: String,
    pub parent_check: Option<&'static str>,
    pub start: String,
    pub expires_at: DateTime<Utc>,
    pub stops: usize,
    pub routes: usize,
    pub can_import: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RecoveryPayload {
    version: u32,
    docs: Vec<serde_json::Value>,
    secrets: Vec<(String, serde_json::Value)>,
}

pub struct Core {
    pub store: Store,
    pub settings: Settings,
    pub settings_path: PathBuf,
    pub ws: Option<Workspace>,
    pub pending_import: Option<OpenedPack>,
    pub pending_file: Option<Vec<u8>>,
    pub pending_gpx: Option<GpxDocument>,
}

fn kind_of(doc: &Doc) -> &'static str {
    match doc.kind {
        DocKind::Event => KIND_EVENT,
        DocKind::Route => KIND_ROUTE,
    }
}

impl Core {
    pub fn new(store: Store, settings: Settings, settings_path: PathBuf) -> Self {
        Core {
            store,
            settings,
            settings_path,
            ws: None,
            pending_import: None,
            pending_file: None,
            pending_gpx: None,
        }
    }

    pub fn vault_status(&self) -> CmdResult<VaultStatus> {
        let mode = |m: KeyMode| match m {
            KeyMode::Keychain => "keychain",
            KeyMode::Passphrase => "passphrase",
        };
        Ok(match self.store.state()? {
            VaultState::Uninitialized => VaultStatus {
                state: "uninitialized",
                mode: None,
            },
            VaultState::Locked(m) => VaultStatus {
                state: "locked",
                mode: Some(mode(m)),
            },
            VaultState::Unlocked(m) => VaultStatus {
                state: "unlocked",
                mode: Some(mode(m)),
            },
        })
    }

    /// Called after every unlock: expired events, their keys and imports are deleted first.
    pub fn after_unlock(&mut self, now: DateTime<Utc>) -> CmdResult<usize> {
        Ok(self.store.purge_expired(now)?.len())
    }

    pub fn lock(&mut self) {
        self.ws = None;
        self.pending_import = None;
        self.pending_file = None;
        self.pending_gpx = None;
        self.store.lock();
    }

    // -- library ------------------------------------------------------------------------------

    pub fn library(&self, now: DateTime<Utc>) -> CmdResult<Vec<DocSummary>> {
        let mut out = Vec::new();
        for kind in [KIND_EVENT, KIND_ROUTE] {
            for rec in self.store.list(kind, now)? {
                let doc: Doc = serde_json::from_slice(&rec.data)?;
                out.push(summarize(&doc));
            }
        }
        for rec in self.store.list(KIND_IMPORTED, now)? {
            let ir: ImportRecord = serde_json::from_slice(&rec.data)?;
            if let Some(l) = ir.latest() {
                out.push(summarize(&publish::doc_from_pack(&l.pack, now)));
            }
        }
        out.sort_by_key(|a| std::cmp::Reverse(a.updated_at));
        Ok(out)
    }

    fn save_doc(&self, doc: &Doc) -> CmdResult<()> {
        if doc.imported.is_some() {
            return Ok(());
        }
        let bytes = Zeroizing::new(serde_json::to_vec(doc)?);
        self.store
            .put(kind_of(doc), &doc.id, doc.store_expiry(), &bytes)?;
        Ok(())
    }

    pub fn open_new(&mut self, doc: Doc, now: DateTime<Utc>) -> CmdResult<DocView> {
        self.save_doc(&doc)?;
        self.ws = Some(Workspace {
            doc,
            history: History::default(),
            saved_at: Some(now),
            selection: None,
        });
        self.view(now)
    }

    pub fn open(&mut self, kind: &str, id: &str, now: DateTime<Utc>) -> CmdResult<DocView> {
        let rec = self.store.get(kind, id, now)?.ok_or_else(|| {
            CmdError::new(
                "not_found",
                "this item no longer exists (deleted or expired)",
            )
        })?;
        let doc = if kind == KIND_IMPORTED {
            let ir: ImportRecord = serde_json::from_slice(&rec.data)?;
            let l = ir
                .latest()
                .ok_or_else(|| CmdError::new("storage", "imported event is unreadable"))?;
            publish::doc_from_pack(&l.pack, now)
        } else {
            serde_json::from_slice(&rec.data)?
        };
        self.ws = Some(Workspace {
            doc,
            history: History::default(),
            saved_at: Some(now),
            selection: Some(Selection::Event),
        });
        self.view(now)
    }

    pub fn close(&mut self) {
        self.ws = None;
    }

    pub fn delete(&mut self, kind: &str, id: &str) -> CmdResult<()> {
        self.store.delete(kind, id)?;
        if kind != KIND_IMPORTED {
            self.store.delete(KIND_SECRET, id)?;
        }
        if self.ws.as_ref().is_some_and(|w| w.doc.id == id) {
            self.ws = None;
        }
        Ok(())
    }

    fn ws(&self) -> CmdResult<&Workspace> {
        self.ws
            .as_ref()
            .ok_or_else(|| CmdError::new("no_document", "no document is open"))
    }

    fn ws_mut(&mut self) -> CmdResult<&mut Workspace> {
        self.ws
            .as_mut()
            .ok_or_else(|| CmdError::new("no_document", "no document is open"))
    }

    pub fn view(&self, now: DateTime<Utc>) -> CmdResult<DocView> {
        let ws = self.ws()?;
        let mut doc = ws.doc.clone();
        let issues = if doc.kind == DocKind::Event && doc.imported.is_none() {
            build_event(&doc, 1, None, now).1
        } else {
            Vec::new()
        };
        let next_rev = doc.published.last().map_or(1, |p| p.rev + 1);
        // The signed bytes stay in Rust; the UI only needs revision numbers and dates.
        for p in &mut doc.published {
            p.event_bytes.clear();
        }
        Ok(DocView {
            routes: doc.routes.iter().map(route_summary).collect(),
            pending_legs: editing::pending_jobs(&doc).len(),
            readonly: doc.imported.is_some(),
            issues,
            can_undo: ws.history.can_undo(),
            can_redo: ws.history.can_redo(),
            saved_at: ws.saved_at,
            selection: ws.selection.clone(),
            next_rev,
            doc,
        })
    }

    // -- editing ------------------------------------------------------------------------------

    pub fn edit(&mut self, op: EditOp, now: DateTime<Utc>) -> CmdResult<DocView> {
        let ws = self.ws_mut()?;
        let before = ws.doc.clone();
        let sel = editing::apply(&mut ws.doc, op)?;
        ws.doc.updated_at = now;
        ws.history.record(before);
        ws.selection = sel;
        self.autosave(now)?;
        self.view(now)
    }

    fn autosave(&mut self, now: DateTime<Utc>) -> CmdResult<()> {
        let doc = self.ws()?.doc.clone();
        self.save_doc(&doc)?;
        self.ws_mut()?.saved_at = Some(now);
        Ok(())
    }

    pub fn save(&mut self, now: DateTime<Utc>) -> CmdResult<DocView> {
        self.autosave(now)?;
        self.view(now)
    }

    pub fn undo(&mut self, now: DateTime<Utc>) -> CmdResult<DocView> {
        let ws = self.ws_mut()?;
        let mut doc = ws.doc.clone();
        if ws.history.undo(&mut doc) {
            ws.doc = doc;
            self.autosave(now)?;
        }
        self.view(now)
    }

    pub fn redo(&mut self, now: DateTime<Utc>) -> CmdResult<DocView> {
        let ws = self.ws_mut()?;
        let mut doc = ws.doc.clone();
        if ws.history.redo(&mut doc) {
            ws.doc = doc;
            self.autosave(now)?;
        }
        self.view(now)
    }

    pub fn select(&mut self, sel: Option<Selection>) -> CmdResult<()> {
        self.ws_mut()?.selection = sel;
        Ok(())
    }

    pub fn jobs(&self) -> CmdResult<Vec<LegJob>> {
        Ok(editing::pending_jobs(&self.ws()?.doc))
    }

    /// Applies routing results (not undo steps of their own) and saves.
    pub fn apply_jobs(
        &mut self,
        results: Vec<(LegJob, Result<JobOutput, String>)>,
        now: DateTime<Utc>,
    ) -> CmdResult<DocView> {
        let ws = self.ws_mut()?;
        let mut changed = false;
        for (job, res) in results {
            changed |= editing::apply_job(&mut ws.doc, &job, res);
        }
        if changed {
            self.autosave(now)?;
        }
        self.view(now)
    }

    pub fn route_to_event(&mut self, now: DateTime<Utc>) -> CmdResult<DocView> {
        let doc = self.ws()?.doc.clone();
        if doc.kind != DocKind::Route {
            return Err(CmdError::new("not_possible", "already an event"));
        }
        let ev = crate::templates::route_to_event(&doc, self.settings.lang, now);
        self.open_new(ev, now)
    }

    pub fn copy_imported(&mut self, now: DateTime<Utc>) -> CmdResult<DocView> {
        let mut doc = self.ws()?.doc.clone();
        if doc.imported.is_none() {
            return Err(CmdError::new("not_possible", "not an imported event"));
        }
        doc.id = new_event_id()?;
        doc.imported = None;
        doc.published.clear();
        doc.meta.expires_at.clear();
        doc.updated_at = now;
        self.open_new(doc, now)
    }

    // -- publishing ---------------------------------------------------------------------------

    /// The event's signing seed, link key and file secret code, created on first use. They never
    /// leave Rust (the code only when the host asks to see or copy it). Secrets stored before
    /// suite 2 get their file code here, once, and keep it for every later revision.
    pub fn secrets(&self, event_id: &str, now: DateTime<Utc>) -> CmdResult<EventSecrets> {
        if let Some(rec) = self.store.get(KIND_SECRET, event_id, now)? {
            let mut s: EventSecrets = serde_json::from_slice(&rec.data)?;
            if s.ensure_file_code()? {
                self.store.put(
                    KIND_SECRET,
                    event_id,
                    rec.expires_at,
                    &Zeroizing::new(serde_json::to_vec(&s)?),
                )?;
            }
            return Ok(s);
        }
        let s = EventSecrets::generate()?;
        self.store.put(
            KIND_SECRET,
            event_id,
            None,
            &Zeroizing::new(serde_json::to_vec(&s)?),
        )?;
        Ok(s)
    }

    pub fn prepare(&self, now: DateTime<Utc>) -> CmdResult<Result<Prepared, Vec<Issue>>> {
        let ws = self.ws()?;
        if ws.doc.imported.is_some() {
            return Err(CmdError::new(
                "read_only",
                "imported events cannot be published",
            ));
        }
        Ok(publish::prepare(&ws.doc, now))
    }

    pub fn publish_preview(
        &self,
        stops_only: bool,
        now: DateTime<Utc>,
    ) -> CmdResult<PublishPreview> {
        let doc_id = self.ws()?.doc.id.clone();
        let previous_rev = self.ws()?.doc.published.last().map(|p| p.rev);
        let prepared = match self.prepare(now)? {
            Ok(p) => p,
            Err(issues) => {
                return Ok(PublishPreview {
                    ok: false,
                    issues,
                    event: None,
                    rev: 0,
                    reused: false,
                    changes: Vec::new(),
                    previous_rev,
                    fragment_len: 0,
                    fragment_max: event_core::MAX_FRAGMENT_CHARS,
                    full_fragment_len: 0,
                    stops_only_fragment_len: None,
                    file_bytes: 0,
                    stops_only: false,
                });
            }
        };
        let secrets = self.secrets(&doc_id, now)?;
        let (sealed, key) = publish::seal_bytes(&prepared.bytes, &secrets)?;
        let file_bytes = publish::file_len(&sealed);
        let full_fragment_len = publish::fragment_len(&sealed, &key);
        let variant = if prepared.event.routes.is_empty() {
            None
        } else {
            let bytes = publish::pack_bytes(&prepared, true)?;
            let (sealed, key) = publish::seal_bytes(&bytes, &secrets)?;
            Some((bytes, publish::fragment_len(&sealed, &key)))
        };
        let stops_only = stops_only && variant.is_some();
        let (shown, fragment_len) = match &variant {
            Some((bytes, len)) if stops_only => (bytes.as_slice(), *len),
            _ => (prepared.bytes.as_slice(), full_fragment_len),
        };
        Ok(PublishPreview {
            ok: true,
            event: Some(serde_json::from_slice(shown)?),
            rev: prepared.event.rev,
            reused: prepared.reused,
            changes: publish::changes(&prepared),
            previous_rev,
            fragment_len,
            fragment_max: event_core::MAX_FRAGMENT_CHARS,
            full_fragment_len,
            stops_only_fragment_len: variant.map(|(_, len)| len),
            file_bytes,
            stops_only,
            issues: prepared.issues,
        })
    }

    /// Seals the next revision and records it (always the full event's bytes). Returns the link
    /// (full, or the stops-only variant of the same revision, §5.5) or the file bytes (always the
    /// full event); the caller copies / writes them and never hands the key to the UI.
    pub fn publish(&mut self, target: PublishTarget, now: DateTime<Utc>) -> CmdResult<Published> {
        let prepared = self.prepare(now)?;
        let prepared = prepared.map_err(|issues| {
            let first = issues
                .iter()
                .find(|i| i.severity == Severity::Error)
                .map_or_else(String::new, |i| format!("{}: {}", i.path, i.message));
            CmdError::new("invalid_event", first)
        })?;
        let doc_id = self.ws()?.doc.id.clone();
        let secrets = self.secrets(&doc_id, now)?;
        let stops_only = matches!(target, PublishTarget::Link { stops_only: true });
        let bytes = publish::pack_bytes(&prepared, stops_only)?;
        let out = match target {
            PublishTarget::Link { .. } => {
                let (sealed, key) = publish::seal_bytes(&bytes, &secrets)?;
                Published::Link(publish::link(&self.settings.invite_site, &sealed, &key)?)
            }
            // Always the full event (§5.5), under the event's file secret code (§5.2 suite 2).
            PublishTarget::File => Published::File(publish::file(&bytes, &secrets)?),
        };
        let ws = self.ws_mut()?;
        publish::record(&mut ws.doc, &prepared);
        ws.doc.updated_at = now;
        let expiry = ws.doc.store_expiry();
        self.autosave(now)?;
        // The keys now expire with the event.
        self.store.put(
            KIND_SECRET,
            &doc_id,
            expiry,
            &Zeroizing::new(serde_json::to_vec(&secrets)?),
        )?;
        Ok(out)
    }

    pub fn secret_code(&self, now: DateTime<Utc>) -> CmdResult<Zeroizing<String>> {
        let id = self.ws()?.doc.id.clone();
        let s = self.secrets(&id, now)?;
        let code = s
            .file_code()
            .ok_or_else(|| CmdError::new("crypto", "file secret code unreadable"))?;
        Ok(Zeroizing::new(code.to_owned()))
    }

    // -- import -------------------------------------------------------------------------------

    fn import_record(&self, id: &str, now: DateTime<Utc>) -> CmdResult<Option<ImportRecord>> {
        Ok(match self.store.get(KIND_IMPORTED, id, now)? {
            Some(rec) => Some(serde_json::from_slice(&rec.data)?),
            None => None,
        })
    }

    pub fn import_preview(
        &mut self,
        opened: OpenedPack,
        now: DateTime<Utc>,
    ) -> CmdResult<ImportPreview> {
        let record = self.import_record(&opened.event.id, now)?;
        let stored = record.as_ref().and_then(ImportRecord::stored);
        let decision = decide_import(&ImportCandidate::from(&opened), stored.as_ref(), now);
        let (name, parent_check, can) = match &decision {
            ImportDecision::Expired => ("expired", None, false),
            ImportDecision::New { .. } => ("new", None, true),
            ImportDecision::DifferentOrganizer => ("different_organizer", None, false),
            ImportDecision::AlreadyImported => ("already", None, false),
            ImportDecision::UpgradeToFull { rev } => {
                // §7.4: replace the stored stops-only variant silently; nothing to confirm.
                let mut rec = record.ok_or_else(|| CmdError::new("storage", "record vanished"))?;
                rec.upgrade_to_full(*rev, &opened);
                self.store.put(
                    KIND_IMPORTED,
                    &opened.event.id,
                    Some(opened.event.expires_at),
                    &Zeroizing::new(serde_json::to_vec(&rec)?),
                )?;
                ("upgraded", None, false)
            }
            ImportDecision::Older { .. } => ("older", None, false),
            ImportDecision::Fork { .. } => ("fork", None, true),
            ImportDecision::Newer { parent, .. } => (
                "newer",
                Some(match parent {
                    ParentCheck::Matches => "matches",
                    ParentCheck::MissedRevisions => "missed_revisions",
                    ParentCheck::Mismatch => "mismatch",
                }),
                true,
            ),
        };
        let e = &opened.event;
        let preview = ImportPreview {
            decision: name,
            stops_only: e.is_stops_only(),
            title: e.title.clone(),
            alias: e.organizer.as_ref().and_then(|o| o.alias.clone()),
            rev: e.rev,
            safety_code: opened.safety_code(),
            parent_check,
            start: e.start.to_rfc3339(),
            expires_at: e.expires_at,
            stops: e.stops.len(),
            routes: e.routes.len(),
            can_import: can,
        };
        self.pending_import = can.then_some(opened);
        Ok(preview)
    }

    pub fn import_confirm(&mut self, now: DateTime<Utc>) -> CmdResult<DocSummary> {
        let opened = self
            .pending_import
            .take()
            .ok_or_else(|| CmdError::new("no_import", "nothing to import"))?;
        let mut rec = self
            .import_record(&opened.event.id, now)?
            .unwrap_or(ImportRecord {
                organizer_pub: b64u(&opened.organizer_pub),
                revisions: Vec::new(),
            });
        rec.revisions.push(ImportedRev {
            rev: opened.event.rev,
            hash: revision_hash_b64u(&opened.event_bytes),
            event_bytes: b64u(&opened.event_bytes),
        });
        let expiry = opened.event.expires_at;
        self.store.put(
            KIND_IMPORTED,
            &opened.event.id,
            Some(expiry),
            &Zeroizing::new(serde_json::to_vec(&rec)?),
        )?;
        Ok(summarize(&publish::doc_from_pack(&opened, now)))
    }

    // -- GPX ----------------------------------------------------------------------------------

    pub fn gpx_loaded(&mut self, doc: GpxDocument) -> GpxPreview {
        let p = gpx_io::preview(&doc);
        self.pending_gpx = Some(doc);
        p
    }

    /// Adds the GPX path as a new route in the open document, or opens a new standalone route.
    pub fn gpx_apply(&mut self, opts: GpxApply, now: DateTime<Utc>) -> CmdResult<DocView> {
        let gpx = self
            .pending_gpx
            .take()
            .ok_or_else(|| CmdError::new("no_gpx", "no GPX file is loaded"))?;
        let path = gpx
            .paths
            .get(opts.path_index)
            .ok_or_else(|| CmdError::new("not_found", "track not found"))?;
        let route = gpx_io::route_from_path(path, &opts)
            .ok_or_else(|| CmdError::new("invalid", "the track has fewer than two points"))?;
        let stops = if opts.waypoints_as_stops {
            gpx_io::waypoint_stops(&gpx)
        } else {
            Vec::new()
        };
        let rid = route.id.clone();
        let editable = self.ws.as_ref().is_some_and(|w| w.doc.imported.is_none());
        if editable {
            let ws = self.ws_mut()?;
            let before = ws.doc.clone();
            ws.doc.routes.push(route);
            ws.doc.stops.extend(stops);
            ws.doc.updated_at = now;
            ws.history.record(before);
            ws.selection = Some(Selection::Route { route_id: rid });
            self.autosave(now)?;
            self.view(now)
        } else {
            let mut doc = crate::templates::new_route(opts.mode, self.settings.lang, now);
            if !route.title.is_empty() {
                doc.meta.title = route.title.clone();
            }
            doc.routes = vec![route];
            doc.stops = stops;
            self.open_new(doc, now)?;
            self.select(Some(Selection::Route { route_id: rid }))?;
            self.view(now)
        }
    }

    // -- recovery -----------------------------------------------------------------------------

    /// Everything needed to keep publishing on another machine: drafts and their keys.
    pub fn recovery_payload(&self, now: DateTime<Utc>) -> CmdResult<Zeroizing<Vec<u8>>> {
        let mut payload = RecoveryPayload {
            version: 1,
            docs: Vec::new(),
            secrets: Vec::new(),
        };
        for kind in [KIND_EVENT, KIND_ROUTE] {
            for rec in self.store.list(kind, now)? {
                payload.docs.push(serde_json::from_slice(&rec.data)?);
            }
        }
        for rec in self.store.list(KIND_SECRET, now)? {
            payload
                .secrets
                .push((rec.id.clone(), serde_json::from_slice(&rec.data)?));
        }
        Ok(Zeroizing::new(serde_json::to_vec(&payload)?))
    }

    /// Restores drafts and keys that are not already present. Returns how many were added.
    pub fn recovery_restore(&mut self, bytes: &[u8], now: DateTime<Utc>) -> CmdResult<usize> {
        let payload: RecoveryPayload = serde_json::from_slice(bytes)?;
        let mut added = 0;
        for v in payload.docs {
            let doc: Doc = serde_json::from_value(v)?;
            // Expiry applies to restored data too.
            if doc.store_expiry().is_some_and(|e| e <= now) {
                continue;
            }
            if self.store.get(kind_of(&doc), &doc.id, now)?.is_none() {
                self.save_doc(&doc)?;
                let secret = payload.secrets.iter().find(|(id, _)| *id == doc.id);
                if let Some((id, s)) = secret
                    && self.store.get(KIND_SECRET, id, now)?.is_none()
                {
                    self.store.put(
                        KIND_SECRET,
                        id,
                        doc.store_expiry(),
                        &Zeroizing::new(serde_json::to_vec(s)?),
                    )?;
                }
                added += 1;
            }
        }
        Ok(added)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum PublishTarget {
    Link { stops_only: bool },
    File,
}

pub enum Published {
    Link(String),
    File(Vec<u8>),
}
