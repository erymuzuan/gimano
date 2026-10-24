// Typed wrappers over the Rust commands (src-tauri/src/commands.rs). The webview has no other
// way to reach the network or the file system.
import { invoke } from "@tauri-apps/api/core";
import type {
  AppInfo, DocSummary, DocView, EditOp, GpxExportManifest, GpxExportRequest, GpxPreview, ImportPreview,
  LatLng, PublishPreview, PublishResult, Selection, Settings, Template, TravelMode, VaultStatus,
} from "./types";

export const api = {
  appInfo: () => invoke<AppInfo>("app_info"),
  settingsSet: (settings: Settings) => invoke<Settings>("settings_set", { settings }),

  vaultStatus: () => invoke<VaultStatus>("vault_status"),
  vaultSetup: (passphrase?: string) => invoke<VaultStatus>("vault_setup", { passphrase: passphrase ?? null }),
  vaultUnlock: (passphrase?: string) => invoke<number>("vault_unlock", { passphrase: passphrase ?? null }),
  vaultLock: () => invoke<void>("vault_lock"),
  deleteAll: (confirm: string) => invoke<void>("delete_all_local_data", { confirm }),

  library: () => invoke<DocSummary[]>("library_list"),
  newEvent: (template: Template, center: LatLng) => invoke<DocView>("doc_new_event", { template, center }),
  newRoute: (mode: TravelMode) => invoke<DocView>("doc_new_route", { mode }),
  open: (kind: string, id: string) => invoke<DocView>("doc_open", { kind, id }),
  close: () => invoke<void>("doc_close"),
  remove: (kind: string, id: string) => invoke<void>("doc_delete", { kind, id }),
  view: () => invoke<DocView>("doc_view"),
  edit: (op: EditOp) => invoke<DocView>("doc_edit", { op }),
  select: (selection: Selection | null) => invoke<void>("doc_select", { selection }),
  undo: () => invoke<DocView>("doc_undo"),
  redo: () => invoke<DocView>("doc_redo"),
  save: () => invoke<DocView>("doc_save"),
  routeToEvent: () => invoke<DocView>("doc_route_to_event"),
  copyImported: () => invoke<DocView>("doc_copy_imported"),

  compute: () => invoke<DocView>("route_compute"),
  checkOnline: () => invoke<boolean>("check_online"),

  publishPreview: (stopsOnly: boolean) => invoke<PublishPreview>("publish_preview", { stopsOnly }),
  publishLink: (stopsOnly: boolean) => invoke<PublishResult>("publish_link", { stopsOnly }),
  publishFile: () => invoke<PublishResult>("publish_file"),
  secretCodeCopy: () => invoke<void>("secret_code_copy"),
  secretCodeReveal: () => invoke<string>("secret_code_reveal"),

  importLinkPreview: (link: string) => invoke<ImportPreview>("import_link_preview", { link }),
  importFilePick: () => invoke<{ fileName: string; size: number; needsCode: boolean } | null>("import_file_pick"),
  importFilePreview: (secretCode: string) => invoke<ImportPreview>("import_file_preview", { secretCode }),
  importConfirm: () => invoke<DocSummary>("import_confirm"),

  gpxPick: () => invoke<GpxPreview | null>("gpx_pick"),
  gpxApply: (options: { pathIndex: number; mode: TravelMode; snap: boolean; stripTimestamps: boolean; waypointsAsStops: boolean }) =>
    invoke<DocView>("gpx_apply", { options }),
  gpxExportManifest: (routeId: string, request: GpxExportRequest) =>
    invoke<GpxExportManifest>("gpx_export_manifest", { args: { routeId, request } }),
  gpxExport: (routeId: string, request: GpxExportRequest) =>
    invoke<string | null>("gpx_export", { args: { routeId, request } }),

  recoveryExport: (passphrase: string) => invoke<string | null>("recovery_export", { passphrase }),
  recoveryImport: (passphrase: string) => invoke<number | null>("recovery_import", { passphrase }),
};

export function errorCode(e: unknown): string {
  return typeof e === "object" && e !== null && "code" in e ? String((e as { code: unknown }).code) : "internal";
}

export function errorMessage(e: unknown): string {
  if (typeof e === "object" && e !== null && "message" in e) return String((e as { message: unknown }).message);
  return String(e);
}
