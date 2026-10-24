// Mirrors of the Rust view types (src-tauri/src/{doc,assemble,core,editing}.rs). The webview
// only ever sees these: no keys, no signed bytes, no links with keys.

export type Lang = "ms" | "en";
export type Template = "kenduri" | "dinner" | "company" | "ride" | "convoy" | "trip" | "other";
export type TravelMode = "car" | "motorcycle" | "bicycle" | "walk";
export type StopKind =
  | "parking" | "dropoff" | "entrance" | "venue" | "meal" | "checkpoint" | "fuel" | "water"
  | "rest" | "prayer" | "accommodation" | "start" | "finish" | "other";
export type LegState = "pending" | "routed" | "matched" | "sketch" | "gpx" | "unresolved";
export type GeometrySource = "routed" | "sketch" | "gpx";

export interface LatLng { lat: number; lng: number }

export interface EventMeta {
  template: Template; title: string; description: string; lang: Lang; tz: string;
  start: string; end: string; expiresAt: string; alias: string;
}

export interface StopDraft {
  id: string; kind: StopKind; label: string; lat: number; lng: number;
  entrance?: LatLng | null; arriveFrom: string; arriveTo: string; dwellMin?: number | null;
  notes: string; audience: string; organizerNote: string;
}

export interface Session { id: string; title: string; start: string; end: string; stopId?: string | null }

export interface RoutePoint { id: string; lat: number; lng: number; stopId?: string | null }

export interface Leg {
  state: LegState; generation: number; geometry: LatLng[]; distanceM: number; durationS?: number | null;
  elevation?: (number | null)[] | null; drawn?: LatLng[] | null; unmatched?: [number, number][]; problem?: string | null;
}

export interface RouteDraft {
  id: string; title: string; mode: TravelMode; day: string; followRoads: boolean;
  points: RoutePoint[]; legs: Leg[];
}

export interface PublishedRev { rev: number; publishedAt: string; hash: string; expiresAt: string }

export interface Doc {
  id: string; kind: "event" | "route"; updatedAt: string; meta: EventMeta;
  stops: StopDraft[]; sessions: Session[]; routes: RouteDraft[]; published: PublishedRev[];
  imported?: { safetyCode: string; organizerPub: string; rev: number; expiresAt: string } | null;
}

export interface RouteSummary {
  distanceM: number; durationS?: number | null; unresolvedLegs: number; ascentM?: number | null;
  descentM?: number | null; source?: GeometrySource | null; navigable: boolean; profile: [number, number][];
}

export interface Issue { path: string; code: string; severity: "error" | "warning"; message: string }

export type Selection =
  | { type: "event" }
  | { type: "stop"; id: string }
  | { type: "session"; id: string }
  | { type: "route"; routeId: string }
  | { type: "point"; routeId: string; index: number };

export interface DocView {
  doc: Doc; routes: RouteSummary[]; issues: Issue[]; canUndo: boolean; canRedo: boolean;
  savedAt?: string | null; readonly: boolean; selection?: Selection | null; pendingLegs: number; nextRev: number;
}

export interface DocSummary {
  id: string; kind: "event" | "route"; title: string; template: Template; start: string; updatedAt: string;
  stopCount: number; routeCount: number; distanceM: number; publishedRev?: number | null; imported: boolean;
  expiresAt?: string | null; preview: [number, number][];
}

export interface Settings { apiBaseUrl: string; tilesOnline: boolean; lang: Lang; inviteSite: string }

export interface VaultStatus { state: "uninitialized" | "locked" | "unlocked"; mode?: "keychain" | "passphrase" | null }

export interface AppInfo {
  version: string; styleUrl: string; settings: Settings; apiPresets: [string, string][];
  vault: VaultStatus; platform: string;
}

export interface Change { kind: "added" | "removed" | "changed"; what: "event" | "stop" | "session" | "route"; label: string; fields: string[] }

export interface PublishPreview {
  ok: boolean; issues: Issue[]; event?: GuestEvent | null; rev: number; reused: boolean; changes: Change[];
  previousRev?: number | null; fragmentLen: number; fragmentMax: number; fileBytes: number;
  /** Link lengths of the full event and of its stops-only variant (null without routes). */
  fullFragmentLen: number; stopsOnlyFragmentLen?: number | null;
  /** The preview (event, fragmentLen) is the stops-only variant (docs/events.md §5.5). */
  stopsOnly: boolean;
}

/** The exact event JSON that goes into the pack (docs/events.md §4). */
export interface GuestEvent {
  id: string; rev: number; title: string; description?: string; lang: Lang; tz: string; start: string; end: string;
  expiresAt: string; template: Template; organizer?: { alias?: string };
  stops: {
    id: string; kind: StopKind; label: string; lat: number; lng: number; entrance?: LatLng;
    arrive?: { from: string; to: string }; dwellMin?: number; notes?: string; audience?: string;
  }[];
  sessions?: { id: string; title: string; start: string; end: string; stopId?: string }[];
  routes?: { id: string; title?: string; mode: TravelMode; day?: string; polyline?: string; source: GeometrySource; distanceM: number; durationS?: number; ascentM?: number; descentM?: number; stopIds: string[] }[];
  variant?: { kind: string; fullHash: string };
}

export interface PublishResult { rev: number; copied: boolean; fragmentLen: number; fileName?: string | null }

export interface ImportPreview {
  /** `upgraded`: the full event replaced a stored stops-only variant (already saved, §7.4). */
  decision: "new" | "already" | "upgraded" | "older" | "fork" | "newer" | "different_organizer" | "expired";
  /** The pack is a stops-only variant: no route lines. */
  stopsOnly: boolean;
  title: string; alias?: string | null; rev: number; safetyCode: string; parentCheck?: string | null;
  start: string; expiresAt: string; stops: number; routes: number; canImport: boolean;
}

export interface GpxPathPreview {
  index: number; kind: "track" | "route"; name?: string | null; pointCount: number; distanceM: number;
  hasElevation: boolean; hasTimes: boolean; preview: [number, number][];
}

export interface GpxPreview {
  paths: GpxPathPreview[]; waypoints: [string | null, number, number][]; pointCount: number;
  hasTimestamps: boolean; hasElevation: boolean;
}

export interface GpxExportRequest {
  acknowledgedPlaintext: boolean; includeStops: boolean; includeElevation: boolean; includeTimestamps: boolean;
}

export interface GpxExportManifest {
  trackName?: string | null; pointCount: number; waypointNames: string[]; hasElevation: boolean; hasTimestamps: boolean;
}

export interface CmdError { code: string; message: string }

export type EditOp =
  | { op: "setMeta"; meta: EventMeta }
  | { op: "addStop"; lat: number; lng: number; kind?: StopKind; label?: string }
  | { op: "updateStop"; stop: StopDraft }
  | { op: "deleteStop"; id: string }
  | { op: "reorderStop"; id: string; delta: number }
  | { op: "nudgeStop"; id: string; eastM: number; northM: number }
  | { op: "addSession"; title: string; start: string; end: string; stopId?: string | null }
  | { op: "updateSession"; session: Session }
  | { op: "deleteSession"; id: string }
  | { op: "addRoute"; mode?: TravelMode }
  | { op: "updateRoute"; routeId: string; title: string; day: string; mode: TravelMode; followRoads: boolean }
  | { op: "deleteRoute"; routeId: string }
  | { op: "addPoint"; routeId: string; lat: number; lng: number; stopId?: string | null }
  | { op: "insertPoint"; routeId: string; legIndex: number; lat: number; lng: number }
  | { op: "movePoint"; routeId: string; index: number; lat: number; lng: number }
  | { op: "nudgePoint"; routeId: string; index: number; eastM: number; northM: number }
  | { op: "deletePoint"; routeId: string; index: number }
  | { op: "reorderPoint"; routeId: string; index: number; delta: number }
  | { op: "setPointStop"; routeId: string; index: number; stop: boolean }
  | { op: "reverse"; routeId: string }
  | { op: "closeLoop"; routeId: string }
  | { op: "outAndBack"; routeId: string }
  | { op: "splitAt"; routeId: string; index: number }
  | { op: "addDrawnLeg"; routeId: string; points: LatLng[] }
  | { op: "keepLegAsSketch"; routeId: string; legIndex: number }
  | { op: "retryLeg"; routeId: string; legIndex: number };
