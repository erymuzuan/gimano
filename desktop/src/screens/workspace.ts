// Event planner + route builder: outline · map · inspector, bottom drawer
// (Jadual / Profil ketinggian / Semakan). Every edit is a typed command to Rust, which keeps the
// document, the undo history and the encrypted autosave.
import { api } from "../api";
import { fail, go, mod, modLabel, register, state, watchOnline } from "../app";
import { field, h, svg, uid } from "../dom";
import { coordText, daysBetween, distance, duration, fromLocalInput, offsetOf, parsePath, toLocalInput, when } from "../format";
import { infoKindName, kindName, legName, modeName, reasonText, t, templateName } from "../i18n";
import { MapView, type MapMode } from "../map";
import { button, confirmDialog, coordsDialog, replace, toast } from "../ui";
import type { DocView, EditOp, EventMeta, Issue, LatLng, RouteDraft, Selection, Session, StopDraft, StopKind, Template, TravelMode } from "../types";
import { openGpxDialog, openGpxExport, tabList } from "./importer";
import { openPublishDialog } from "./publish";

const KINDS: StopKind[] = ["parking", "dropoff", "entrance", "venue", "meal", "checkpoint", "fuel", "water", "rest", "prayer", "accommodation", "start", "finish", "other"];
const TEMPLATES: Template[] = ["kenduri", "dinner", "company", "meeting", "seminar", "ride", "convoy", "trip", "picnic", "hike", "other"];
const MODES: TravelMode[] = ["bicycle", "motorcycle", "car", "walk"];

register("workspace", (root, arg) => {
  let view = arg as DocView;
  let sel: Selection | null = view.selection ?? { type: "event" };
  let activeRoute: string | undefined = selRoute(sel) ?? view.doc.routes[0]?.id;
  let mode: MapMode = "select";
  let computing = false;
  let inspectorOpen = false;

  // ---- skeleton ------------------------------------------------------------------------------
  const crumbTitle = h("span", { "aria-current": "page" });
  const status = h("span", { class: "status", "aria-live": "polite" });
  const online = h("span", { class: "online", role: "status" });
  const undoBtn = button(t("undo"), () => void run(api.undo()), "ghost", { "aria-keyshortcuts": "Meta+Z Control+Z", title: `${modLabel()}Z` });
  const redoBtn = button(t("redo"), () => void run(api.redo()), "ghost", { "aria-keyshortcuts": "Meta+Shift+Z Control+Shift+Z", title: `⇧${modLabel()}Z` });
  const saveBtn = button(t("saveDraft"), () => void run(api.save(), t("saveDraft") + " ✓"), "secondary", { "aria-keyshortcuts": "Meta+S Control+S", title: `${modLabel()}S` });
  const publishBtn = button("", () => openPublishDialog(() => void run(api.view())), "primary");
  const extraBtn = h("span", {});
  const inspectorToggle = button(t("summary"), () => { inspectorOpen = !inspectorOpen; layout(); }, "ghost", { class: "btn ghost inspector-toggle", "aria-expanded": "false" });
  const outline = h("nav", { class: "outline", "aria-label": t("event") });
  const toolbar = h("div", { class: "map-toolbar", role: "toolbar", "aria-label": t("route") });
  const mapEl = h("div", { class: "map", role: "application", "aria-label": "Peta / Map" });
  const offlineBanner = h("p", { class: "map-offline", hidden: true }, t("mapOffline"));
  const inspector = h("aside", { class: "inspector", "aria-label": t("summary") });
  const drawerBody = h("div", { class: "drawer-body" });
  const schedulePanel = h("div", { class: "drawer-panel" });
  const profilePanel = h("div", { class: "drawer-panel" });
  const checkPanel = h("div", { class: "drawer-panel" });
  const drawer = h("section", { class: "drawer", "aria-label": t("checkTab") },
    tabList([t("scheduleTab"), t("profileTab"), t("checkTab")], [schedulePanel, profilePanel, checkPanel], undefined, 0),
    drawerBody,
  );
  drawerBody.append(schedulePanel, profilePanel, checkPanel);

  const shell = h("div", { class: "ws" },
    h("header", { class: "titlebar" },
      h("nav", { class: "breadcrumb", "aria-label": "breadcrumb" },
        h("button", { type: "button", class: "link", onclick: () => void back() }, t("library")),
        h("span", { "aria-hidden": "true" }, " / "), crumbTitle),
      status, online,
      h("div", { class: "toolbar" }, undoBtn, redoBtn, extraBtn, saveBtn, publishBtn, inspectorToggle),
    ),
    h("div", { class: "ws-body" },
      outline,
      h("main", { class: "map-area" }, toolbar, mapEl, offlineBanner),
      inspector,
    ),
    drawer,
  );
  root.append(shell);

  const tilesOnline = state.info?.settings.tilesOnline ?? true;
  offlineBanner.hidden = tilesOnline;
  const map = new MapView(mapEl, tilesOnline, state.info?.styleUrl ?? "", {
    selectStop: (id) => select({ type: "stop", id }),
    selectPoint: (routeId, index) => select({ type: "point", routeId, index }),
    moveStop: (id, p) => {
      const s = view.doc.stops.find((x) => x.id === id);
      if (s) void edit({ op: "updateStop", stop: { ...s, lat: p.lat, lng: p.lng } });
    },
    moveEntrance: (id, p) => {
      const s = view.doc.stops.find((x) => x.id === id);
      if (s) void edit({ op: "updateStop", stop: { ...s, entrance: p } });
    },
    movePoint: (routeId, index, p) => void edit({ op: "movePoint", routeId, index, lat: p.lat, lng: p.lng }),
    mapClick: (p) => {
      if (mode === "point" && activeRoute) void edit({ op: "addPoint", routeId: activeRoute, lat: p.lat, lng: p.lng });
    },
    lineClick: (routeId, legIndex, p) => void edit({ op: "insertPoint", routeId, legIndex, lat: p.lat, lng: p.lng }),
    drawEnd: (points) => {
      if (activeRoute) void edit({ op: "addDrawnLeg", routeId: activeRoute, points });
    },
  });
  const ro = new ResizeObserver(() => map.resize());
  ro.observe(mapEl);
  const unwatch = watchOnline((on) => {
    online.className = on ? "online on" : "online off";
    online.textContent = on ? `● ${t("online")}` : `○ ${t("offline")}`;
    online.title = on ? t("onlineHint") : t("offlineHint");
  });

  // ---- data flow -------------------------------------------------------------------------------
  function selRoute(s: Selection | null): string | undefined {
    return s?.type === "route" || s?.type === "point" ? s.routeId : undefined;
  }

  async function run(p: Promise<DocView>, okMsg?: string): Promise<void> {
    try {
      view = await p;
      if (view.selection) sel = view.selection;
      const r = selRoute(sel);
      if (r) activeRoute = r;
      if (activeRoute && !view.doc.routes.some((x) => x.id === activeRoute)) activeRoute = view.doc.routes[0]?.id;
      render();
      if (okMsg) toast(okMsg, "ok");
      maybeCompute();
    } catch (e) {
      fail(e);
    }
  }

  const edit = (op: EditOp) => run(api.edit(op));

  function maybeCompute(): void {
    if (computing || view.readonly || view.pendingLegs === 0) return;
    computing = true;
    renderStatus();
    api.compute()
      .then((v) => {
        view = v;
        render();
      })
      .catch(fail)
      .finally(() => {
        computing = false;
        renderStatus();
        if (view.pendingLegs > 0 && !view.readonly) {
          // Edits arrived while calculating; their legs are still pending.
          window.setTimeout(maybeCompute, 300);
        }
      });
  }

  function select(s: Selection | null): void {
    sel = s;
    const r = selRoute(s);
    if (r) activeRoute = r;
    if (window.innerWidth < 1280) inspectorOpen = true;
    void api.select(s).catch(() => undefined);
    render();
  }

  async function back(): Promise<void> {
    try { await api.save(); await api.close(); } catch { /* still leave */ }
    go("library");
  }

  // ---- rendering -------------------------------------------------------------------------------
  function issuesFor(prefix: string): Issue[] {
    return view.issues.filter((i) => i.path === prefix || i.path.startsWith(`${prefix}.`) || i.path.startsWith(`${prefix}[`));
  }
  const errText = (path: string) => {
    const i = view.issues.find((x) => x.path === path && x.severity === "error");
    return i ? reasonText(i.message) : undefined;
  };

  function render(): void {
    // Keep keyboard focus across re-renders (fields commit on change).
    const active = document.activeElement as HTMLElement | null;
    const fkey = active?.dataset?.fkey;
    renderStatus();
    renderOutline();
    renderToolbar();
    renderInspector();
    renderDrawer();
    map.render(view, activeRoute, sel);
    map.setMode(view.readonly ? "select" : mode);
    layout();
    if (fkey) (shell.querySelector(`[data-fkey="${CSS.escape(fkey)}"]`) as HTMLElement | null)?.focus();
  }

  function layout(): void {
    shell.classList.toggle("inspector-open", inspectorOpen);
    inspectorToggle.setAttribute("aria-expanded", String(inspectorOpen));
  }

  function renderStatus(): void {
    const d = view.doc;
    crumbTitle.textContent = d.meta.title || t("newEvent");
    status.textContent = view.readonly
      ? t("readonly")
      : computing
        ? t("computing")
        : view.savedAt ? t("savedAt", { t: new Date(view.savedAt).toLocaleTimeString() }) : t("unsaved");
    undoBtn.disabled = !view.canUndo || view.readonly;
    redoBtn.disabled = !view.canRedo || view.readonly;
    saveBtn.hidden = view.readonly;
    publishBtn.hidden = view.readonly || d.kind !== "event";
    publishBtn.textContent = t("publishTo", { n: view.nextRev });
    const errors = view.issues.filter((i) => i.severity === "error").length;
    publishBtn.title = errors ? t("cannotPublish") : "";
    extraBtn.replaceChildren();
    if (view.readonly) extraBtn.append(button(t("copyAsDraft"), () => void run(api.copyImported())));
    else if (d.kind === "route") extraBtn.append(button(t("makeEvent"), () => void run(api.routeToEvent())));
    if (!view.readonly) extraBtn.append(button("GPX", async () => {
      try { const p = await api.gpxPick(); if (p) openGpxDialog(p, (v) => { view = v; sel = v.selection ?? sel; activeRoute = selRoute(sel) ?? activeRoute; render(); maybeCompute(); }); }
      catch (e) { fail(e); }
    }, "ghost", { "aria-label": t("gpxTitle") }));
  }

  function outlineItem(label: string, s: Selection, opts: { key: string; meta?: string; bad?: boolean; current: boolean; extra?: HTMLElement[] }): HTMLElement {
    return h("li", { class: opts.current ? "current" : "" },
      h("button", {
        type: "button", class: "outline-item", "data-fkey": opts.key, "aria-current": opts.current ? "true" : null,
        onclick: () => select(s),
      },
      h("span", { class: "oi-label" }, label),
      opts.meta ? h("span", { class: "oi-meta" }, opts.meta) : null,
      opts.bad ? h("span", { class: "dot-error", "aria-label": t("error") }, "!") : null),
      ...(opts.extra ?? []),
    );
  }

  function renderOutline(): void {
    const d = view.doc;
    const isEvent = d.kind === "event";
    const days = isEvent ? daysBetween(d.meta.start, d.meta.end) : [];
    const stopIssues = (i: number) => issuesFor(`stops[${i}]`).some((x) => x.severity === "error");
    replace(outline,
      isEvent ? h("section", {},
        h("h2", {}, t("event")),
        h("ul", {}, outlineItem(t("details"), { type: "event" }, {
          key: "o-event", current: sel?.type === "event", meta: templateName(d.meta.template),
          bad: view.issues.some((i) => i.severity === "error" && !/^(stops|routes|sessions)\[/.test(i.path)),
        })),
        days.length ? [h("h3", {}, t("days")), h("ul", { class: "days" }, ...days.map((day, i) => {
          const routes = d.routes.filter((r) => r.day === day);
          const sessions = d.sessions.filter((s) => s.start.startsWith(day));
          return h("li", {}, h("span", { class: "day-label" }, `${i + 1}. ${day}`),
            h("span", { class: "oi-meta" }, `${sessions.length} ${t("sessionTitle").toLowerCase()} · ${routes.length} ${t("route").toLowerCase()}`));
        }))] : null,
      ) : null,
      h("section", {},
        h("h2", {}, t("stopsH")),
        h("ol", { class: "stops" }, ...d.stops.map((s, i) => outlineItem(`${i + 1}. ${s.label || "—"}`, { type: "stop", id: s.id }, {
          key: `o-stop-${s.id}`, current: sel?.type === "stop" && sel.id === s.id,
          meta: [kindName(s.kind), s.arriveFrom ? when(s.arriveFrom).slice(-5) : ""].filter(Boolean).join(" · "),
          bad: stopIssues(i),
        }))),
        view.readonly ? null : button(`+ ${t("addStop")}`, () => {
          const c = map.center();
          void edit({ op: "addStop", lat: c.lat, lng: c.lng });
        }, "ghost"),
      ),
      h("section", {},
        h("h2", {}, t("routesH")),
        h("ul", {}, ...d.routes.map((r, i) => {
          const sum = view.routes[i];
          const belum = sum?.unresolvedLegs ?? 0;
          return outlineItem(r.title || `${t("route")} ${i + 1}`, { type: "route", routeId: r.id }, {
            key: `o-route-${r.id}`, current: activeRoute === r.id && (sel?.type === "route" || sel?.type === "point"),
            meta: `${modeName(r.mode)} · ${distance(sum?.distanceM ?? 0)}${belum ? ` · ${belum} ${t("pending")}` : ""}${r.day ? ` · ${r.day}` : ""}`,
            bad: issuesFor(`routes[${i}]`).some((x) => x.severity === "error"),
          });
        })),
        view.readonly ? null : button(`+ ${t("addRoute")}`, () => void edit({ op: "addRoute", mode: d.routes[0]?.mode ?? "car" }), "ghost"),
      ),
    );
  }

  function renderToolbar(): void {
    const r = view.doc.routes.find((x) => x.id === activeRoute);
    if (!r || view.readonly) {
      replace(toolbar, h("span", { class: "muted" }, view.readonly ? t("readonly") : t("noRoute")));
      return;
    }
    const name = uid("mode");
    const seg = (m: MapMode, label: string, key: string) => h("label", { class: `seg${mode === m ? " on" : ""}` },
      h("input", { type: "radio", name, checked: mode === m, "aria-keyshortcuts": key, "data-fkey": `tb-${m}`, onchange: () => { mode = m; render(); } }), label);
    const follow = h("input", { type: "checkbox", checked: r.followRoads, "data-fkey": "tb-follow", onchange: (e: Event) =>
      void edit({ op: "updateRoute", routeId: r.id, title: r.title, day: r.day, mode: r.mode, followRoads: (e.target as HTMLInputElement).checked }) });
    replace(toolbar,
      h("span", { class: "tb-route" }, r.title || t("route")),
      h("div", { class: "segmented", role: "radiogroup", "aria-label": t("route") },
        seg("select", `${t("selectMode")} (Esc)`, "Escape"), seg("point", `${t("pointToPoint")} (P)`, "P"), seg("draw", `${t("draw")} (D)`, "D")),
      h("label", { class: "check" }, follow, " ", t("followRoads")),
      r.followRoads ? null : h("span", { class: "badge badge-sketch" }, t("sketchLabel")),
      h("span", { class: "muted small tb-help" }, mode === "draw" ? t("drawHint") : t("modeHelp")),
    );
  }

  // ---- inspector ------------------------------------------------------------------------------
  function input(value: string, onchange: (v: string) => void, attrs: Record<string, string | number> = {}): HTMLInputElement {
    const el = h("input", { class: "input", value, ...attrs });
    el.disabled = view.readonly;
    el.addEventListener("change", () => onchange(el.value));
    return el;
  }
  function textarea(value: string, onchange: (v: string) => void, attrs: Record<string, string | number> = {}): HTMLTextAreaElement {
    const el = h("textarea", { class: "input", rows: 3, ...attrs });
    el.value = value;
    el.disabled = view.readonly;
    el.addEventListener("change", () => onchange(el.value));
    return el;
  }
  function select_<T extends string>(value: T, options: [T, string][], onchange: (v: T) => void, attrs: Record<string, string> = {}): HTMLSelectElement {
    const el = h("select", { class: "input", ...attrs }, ...options.map(([v, l]) => h("option", { value: v }, l)));
    el.value = value;
    el.disabled = view.readonly;
    el.addEventListener("change", () => onchange(el.value as T));
    return el;
  }
  const dt = (value: string, offset: string, onchange: (v: string) => void, key: string) =>
    input(toLocalInput(value), (v) => onchange(fromLocalInput(v, offset)), { type: "datetime-local", "data-fkey": key });

  function renderInspector(): void {
    const d = view.doc;
    const close = h("button", { type: "button", class: "icon-btn inspector-close", "aria-label": t("close"), onclick: () => { inspectorOpen = false; layout(); } }, "×");
    const cur = sel;
    if (cur?.type === "stop") {
      const s = d.stops.find((x) => x.id === cur.id);
      if (s) return replace(inspector, close, stopInspector(s, d.stops.indexOf(s)));
    }
    if (cur?.type === "session") {
      const s = d.sessions.find((x) => x.id === cur.id);
      if (s) return replace(inspector, close, sessionInspector(s));
    }
    if (activeRoute && (sel?.type === "route" || sel?.type === "point" || d.kind === "route")) {
      const r = d.routes.find((x) => x.id === activeRoute);
      if (r) return replace(inspector, close, routeInspector(r, d.routes.indexOf(r)));
    }
    if (d.kind === "event") return replace(inspector, close, eventInspector(d.meta));
    replace(inspector, close, h("p", { class: "muted" }, t("noRoute")));
  }

  function eventInspector(m: EventMeta): HTMLElement {
    const set = (patch: Partial<EventMeta>) => void edit({ op: "setMeta", meta: { ...m, ...patch } });
    const off = offsetOf(m.start);
    return h("div", { class: "stack" },
      h("h2", {}, t("details")),
      field(t("title"), input(m.title, (v) => set({ title: v }), { "data-fkey": "ev-title", maxlength: 120 }), { path: "title", error: errText("title") }),
      field(t("description"), textarea(m.description, (v) => set({ description: v }), { "data-fkey": "ev-desc", maxlength: 2000 }), { error: errText("description") }),
      field(t("template"), select_(m.template, TEMPLATES.map((x) => [x, templateName(x)]), (v) => set({ template: v }), { "data-fkey": "ev-tpl" })),
      field(t("lang"), select_(m.lang, [["ms", "Bahasa Melayu"], ["en", "English"]], (v) => set({ lang: v }), { "data-fkey": "ev-lang" })),
      field(t("tz"), input(m.tz, (v) => set({ tz: v }), { "data-fkey": "ev-tz" }), { error: errText("tz") }),
      field(t("start"), dt(m.start, off, (v) => set({ start: v }), "ev-start"), { error: errText("start") }),
      field(t("end"), dt(m.end, offsetOf(m.end, off), (v) => set({ end: v }), "ev-end"), { error: errText("end") }),
      field(t("expiresAt"), input(m.expiresAt, (v) => set({ expiresAt: v.trim() }), { "data-fkey": "ev-exp", placeholder: "2026-11-16T08:00:00Z" }), { error: errText("expiresAt") }),
      field(t("alias"), input(m.alias, (v) => set({ alias: v }), { "data-fkey": "ev-alias", maxlength: 80 }), { error: errText("organizer.alias") }),
      view.doc.info?.length ? h("section", { class: "info-blocks" },
        h("h3", {}, t("infoH")),
        h("p", { class: "hint" }, t("infoReadOnly")),
        h("dl", {}, ...view.doc.info.map((b) => [h("dt", {}, infoKindName(b.kind)), h("dd", { class: "info-text" }, b.text)])),
      ) : null,
    );
  }

  function stopInspector(s: StopDraft, i: number): HTMLElement {
    const p = `stops[${i}]`;
    const set = (patch: Partial<StopDraft>) => void edit({ op: "updateStop", stop: { ...s, ...patch } });
    const off = offsetOf(view.doc.meta.start);
    const typeCoords = async (cur: LatLng, apply: (p: LatLng) => void) => {
      const r = await coordsDialog(cur);
      if (r) apply(r);
    };
    return h("div", { class: "stack" },
      h("h2", {}, `${t("stop")} ${i + 1}`),
      field(t("label"), input(s.label, (v) => set({ label: v }), { "data-fkey": "st-label", maxlength: 80 }), { error: errText(`${p}.label`) }),
      field(t("kind"), select_(s.kind, KINDS.map((k) => [k, kindName(k)]), (v) => set({ kind: v }), { "data-fkey": "st-kind" })),
      h("div", { class: "field" }, h("span", { class: "label" }, t("pin")),
        h("p", { class: "mono" }, coordText(s.lat, s.lng)),
        errText(`${p}.lat`) ? h("p", { class: "error" }, errText(`${p}.lat`)!) : null,
        view.readonly ? null : button(t("typeCoords"), () => void typeCoords(s, (q) => set(q)), "ghost", { "aria-keyshortcuts": "Enter" })),
      h("div", { class: "field" }, h("span", { class: "label" }, t("entrance")),
        s.entrance ? h("p", { class: "mono" }, coordText(s.entrance.lat, s.entrance.lng)) : null,
        h("p", { class: "hint" }, t("entranceHint")),
        view.readonly ? null : h("div", { class: "row" },
          button(s.entrance ? t("typeCoords") : t("addEntrance"), () => void typeCoords(s.entrance ?? { lat: s.lat - 0.0005, lng: s.lng + 0.0005 }, (q) => set({ entrance: q })), "ghost"),
          s.entrance ? button(t("removeEntrance"), () => set({ entrance: null }), "ghost") : null)),
      h("div", { class: "grid2" },
        field(t("arriveFrom"), dt(s.arriveFrom, off, (v) => set({ arriveFrom: v }), "st-af"), { error: errText(`${p}.arrive`) }),
        field(t("arriveTo"), dt(s.arriveTo, off, (v) => set({ arriveTo: v }), "st-at"))),
      field(t("dwell"), input(s.dwellMin != null ? String(s.dwellMin) : "", (v) => set({ dwellMin: v.trim() ? Math.max(0, Math.round(Number(v))) : null }), { type: "number", min: 0, max: 20160, "data-fkey": "st-dwell" }), { error: errText(`${p}.dwellMin`) }),
      field(t("notes"), textarea(s.notes, (v) => set({ notes: v }), { "data-fkey": "st-notes", maxlength: 500 }), { error: errText(`${p}.notes`) }),
      field(t("audience"), input(s.audience, (v) => set({ audience: v }), { "data-fkey": "st-aud", maxlength: 40 }), { error: errText(`${p}.audience`) }),
      h("div", { class: "org-note" },
        field(`🔒 ${t("orgNote")}`, textarea(s.organizerNote, (v) => set({ organizerNote: v }), { "data-fkey": "st-org" }), { hint: t("orgNoteHint") })),
      view.readonly ? null : h("div", { class: "row" },
        button(t("moveUp"), () => void edit({ op: "reorderStop", id: s.id, delta: -1 }), "ghost", { "aria-keyshortcuts": "Alt+ArrowUp" }),
        button(t("moveDown"), () => void edit({ op: "reorderStop", id: s.id, delta: 1 }), "ghost", { "aria-keyshortcuts": "Alt+ArrowDown" }),
        activeRoute ? button(`+ ${t("route")}`, () => void edit({ op: "addPoint", routeId: activeRoute!, lat: s.lat, lng: s.lng, stopId: s.id }), "ghost") : null,
        button(t("deleteStop"), async () => {
          if (await confirmDialog(`${t("deleteStop")}: ${s.label}?`, t("delete"), true)) void edit({ op: "deleteStop", id: s.id });
        }, "danger")),
    );
  }

  function sessionInspector(s: Session): HTMLElement {
    const set = (patch: Partial<Session>) => void edit({ op: "updateSession", session: { ...s, ...patch } });
    const off = offsetOf(s.start);
    return h("div", { class: "stack" },
      h("h2", {}, t("sessionTitle")),
      field(t("title"), input(s.title, (v) => set({ title: v }), { "data-fkey": "se-title", maxlength: 120 })),
      field(t("start"), dt(s.start, off, (v) => v && set({ start: v }), "se-start")),
      field(t("end"), dt(s.end, off, (v) => v && set({ end: v }), "se-end")),
      field(t("atStop"), select_(s.stopId ?? "", [["", t("none")], ...view.doc.stops.map((x) => [x.id, x.label] as [string, string])], (v) => set({ stopId: v || null }))),
      view.readonly ? null : button(t("delete"), () => void edit({ op: "deleteSession", id: s.id }), "danger"),
    );
  }

  function routeInspector(r: RouteDraft, ri: number): HTMLElement {
    const sum = view.routes[ri];
    const upd = (patch: Partial<RouteDraft>) => {
      const n = { ...r, ...patch };
      void edit({ op: "updateRoute", routeId: r.id, title: n.title, day: n.day, mode: n.mode, followRoads: n.followRoads });
    };
    const pointSel = sel?.type === "point" && sel.routeId === r.id ? sel.index : undefined;
    const name = uid("tm");
    const ro_ = view.readonly;
    const modeSeg = h("div", { class: "segmented", role: "radiogroup", "aria-label": t("mode") }, ...MODES.map((m) =>
      h("label", { class: `seg${r.mode === m ? " on" : ""}` },
        h("input", { type: "radio", name, checked: r.mode === m, disabled: ro_, "data-fkey": `rt-mode-${m}`, onchange: () => upd({ mode: m }) }), modeName(m))));
    const days = daysBetween(view.doc.meta.start, view.doc.meta.end);
    const dayInput = days.length
      ? select_(r.day, [["", t("none")], ...days.map((x) => [x, x] as [string, string])], (v) => upd({ day: v }), { "data-fkey": "rt-day" })
      : input(r.day, (v) => upd({ day: v.trim() }), { "data-fkey": "rt-day", placeholder: "2026-11-15" });
    const hasEle = sum?.ascentM != null;
    const legs = h("ol", { class: "legs" }, ...r.legs.map((l, j) => {
      const bad = l.state === "pending" || l.state === "unresolved";
      const unmatched = l.state === "matched" && (l.unmatched?.length ?? 0) > 0;
      return h("li", { class: bad ? "leg bad" : unmatched ? "leg warn" : "leg" },
        h("span", { class: "leg-name" }, t("leg", { n: j + 1 })),
        h("span", { class: `badge leg-${l.state}` }, legName(l.state)),
        h("span", { class: "leg-dist" }, bad ? (l.problem ? `(${l.problem})` : "") : `${distance(l.distanceM)}${l.durationS ? ` · ${duration(l.durationS)}` : ""}`),
        unmatched ? h("p", { class: "hint warn" }, t("unmatchedHint")) : null,
        !ro_ && (bad || unmatched) ? h("div", { class: "row" },
          button(t("retry"), () => void edit({ op: "retryLeg", routeId: r.id, legIndex: j }), "ghost"),
          unmatched && j === r.legs.length - 1 ? button(t("redraw"), () => { mode = "draw"; void edit({ op: "deletePoint", routeId: r.id, index: j + 1 }); }, "ghost") : null,
          unmatched ? button(t("addPointHint"), () => { mode = "point"; render(); }, "ghost") : null,
          button(t("keepSketch"), () => void edit({ op: "keepLegAsSketch", routeId: r.id, legIndex: j }), "ghost")) : null,
      );
    }));
    const pt = pointSel !== undefined ? r.points[pointSel] : undefined;
    return h("div", { class: "stack" },
      h("h2", {}, r.title || t("route")),
      field(t("title"), input(r.title, (v) => upd({ title: v }), { "data-fkey": "rt-title", maxlength: 80 })),
      h("div", { class: "field" }, h("span", { class: "label" }, t("mode")), modeSeg),
      view.doc.kind === "event" ? field(t("day"), dayInput, { error: errText(`routes[${ri}].day`) }) : null,
      h("label", { class: "check" }, h("input", { type: "checkbox", checked: r.followRoads, disabled: ro_, onchange: (e: Event) => upd({ followRoads: (e.target as HTMLInputElement).checked }) }), " ", t("followRoads")),
      r.followRoads ? null : h("p", { class: "badge badge-sketch" }, t("sketchLabel")),
      h("section", { class: "summary", "aria-label": t("summary") },
        h("dl", { class: "kv" },
          h("dt", {}, t("distance")), h("dd", {}, distance(sum?.distanceM ?? 0)),
          h("dt", {}, t("moving")), h("dd", {}, sum?.durationS != null ? duration(sum.durationS) : t("unavailable")),
          h("dt", {}, t("ascent")), h("dd", {}, hasEle ? `${sum!.ascentM} m` : t("unavailable")),
          h("dt", {}, t("descent")), h("dd", {}, hasEle ? `${sum!.descentM} m` : t("unavailable")),
        ),
        hasEle ? null : h("p", { class: "hint" }, t("elevationNote")),
        sum && !sum.navigable && sum.source ? h("p", { class: "hint warn" }, t("sketchLabel")) : null,
      ),
      pt ? h("section", { class: "point-panel stack", "aria-label": t("point", { n: (pointSel ?? 0) + 1 }) },
        h("h3", {}, `${t("point", { n: (pointSel ?? 0) + 1 })} · ${pt.stopId ? view.doc.stops.find((s) => s.id === pt.stopId)?.label ?? t("stop") : t("shaping")}`),
        h("p", { class: "mono" }, coordText(pt.lat, pt.lng)),
        ro_ ? null : [
          h("label", { class: "check" }, h("input", { type: "checkbox", checked: !!pt.stopId, onchange: (e: Event) =>
            void edit({ op: "setPointStop", routeId: r.id, index: pointSel!, stop: (e.target as HTMLInputElement).checked }) }), " ", t("isStop")),
          h("div", { class: "row" },
            button(t("typeCoords"), async () => { const q = await coordsDialog(pt); if (q) void edit({ op: "movePoint", routeId: r.id, index: pointSel!, ...q }); }, "ghost", { "aria-keyshortcuts": "Enter" }),
            button(t("moveUp"), () => void edit({ op: "reorderPoint", routeId: r.id, index: pointSel!, delta: -1 }), "ghost", { "aria-keyshortcuts": "Alt+ArrowUp" }),
            button(t("moveDown"), () => void edit({ op: "reorderPoint", routeId: r.id, index: pointSel!, delta: 1 }), "ghost", { "aria-keyshortcuts": "Alt+ArrowDown" }),
            button(t("splitDay"), () => void edit({ op: "splitAt", routeId: r.id, index: pointSel! }), "ghost"),
            button(t("deletePoint"), () => void edit({ op: "deletePoint", routeId: r.id, index: pointSel! }), "danger", { "aria-keyshortcuts": "Delete" })),
        ],
      ) : null,
      h("h3", {}, `${t("legs")} (${r.legs.length})`),
      h("ol", { class: "points", "aria-label": t("route") }, ...r.points.map((p, j) => h("li", {},
        h("button", { type: "button", class: `outline-item${pointSel === j ? " current" : ""}`, "data-fkey": `pt-${r.id}-${j}`, "aria-current": pointSel === j ? "true" : null,
          onclick: () => select({ type: "point", routeId: r.id, index: j }) },
        `${j + 1}. ${p.stopId ? view.doc.stops.find((s) => s.id === p.stopId)?.label ?? t("stop") : t("shaping")}`)))),
      legs,
      ro_ ? null : h("div", { class: "row wrap" },
        view.pendingLegs ? button(t("compute"), () => maybeCompute(), "secondary") : null,
        button(t("reverse"), () => void edit({ op: "reverse", routeId: r.id }), "ghost"),
        button(t("closeLoop"), () => void edit({ op: "closeLoop", routeId: r.id }), "ghost"),
        button(t("outAndBack"), () => void edit({ op: "outAndBack", routeId: r.id }), "ghost"),
        button(t("exportGpx"), () => openGpxExport(r.id), "ghost"),
        button(t("deleteRoute"), async () => {
          if (await confirmDialog(`${t("deleteRoute")}?`, t("delete"), true)) void edit({ op: "deleteRoute", routeId: r.id });
        }, "danger")),
    );
  }

  // ---- drawer ---------------------------------------------------------------------------------
  function renderDrawer(): void {
    const d = view.doc;
    // Schedule
    const days = daysBetween(d.meta.start, d.meta.end);
    replace(schedulePanel,
      d.sessions.length
        ? h("table", { class: "sessions" },
          h("thead", {}, h("tr", {}, h("th", {}, t("start")), h("th", {}, t("end")), h("th", {}, t("sessionTitle")), h("th", {}, t("atStop")))),
          h("tbody", {}, ...d.sessions.map((s) => h("tr", { class: sel?.type === "session" && sel.id === s.id ? "current" : "" },
            h("td", {}, when(s.start)), h("td", {}, when(s.end)),
            h("td", {}, h("button", { type: "button", class: "link", onclick: () => select({ type: "session", id: s.id }) }, s.title)),
            h("td", {}, d.stops.find((x) => x.id === s.stopId)?.label ?? t("none"))))))
        : h("p", { class: "muted" }, t("noSessions")),
      view.readonly || d.kind !== "event" ? null : button(`+ ${t("addSession")}`, () => {
        const day = days[0] ?? d.meta.start.slice(0, 10);
        const off = offsetOf(d.meta.start);
        void edit({ op: "addSession", title: t("sessionTitle"), start: `${day}T12:00:00${off}`, end: `${day}T13:00:00${off}` });
      }, "ghost"),
    );
    // Elevation profile
    const ri = d.routes.findIndex((r) => r.id === activeRoute);
    const sum = ri >= 0 ? view.routes[ri] : undefined;
    if (sum && sum.profile.length > 1) {
      const w = 800, ht = 120, pad = 6;
      const xs = sum.profile.map((p) => p[0]), ys = sum.profile.map((p) => p[1]);
      const [x1, y0, y1] = [Math.max(...xs), Math.min(...ys), Math.max(...ys)];
      const pts = sum.profile.map(([x, y]) => `${(pad + (x / Math.max(x1, 1)) * (w - 2 * pad)).toFixed(1)},${(ht - pad - ((y - y0) / Math.max(y1 - y0, 1)) * (ht - 2 * pad)).toFixed(1)}`).join(" ");
      replace(profilePanel,
        svg("svg", { viewBox: `0 0 ${w} ${ht}`, class: "profile", role: "img", "aria-label": `${t("profileTab")}: ${Math.round(y0)}–${Math.round(y1)} m, ${t("ascent")} ${sum.ascentM} m, ${t("descent")} ${sum.descentM} m` },
          svg("polyline", { points: pts, fill: "none", stroke: "#0047AB", "stroke-width": 2 })),
        h("p", { class: "small muted" }, `${Math.round(y0)}–${Math.round(y1)} m · GPX`));
    } else {
      replace(profilePanel, h("p", {}, `${t("profileTab")}: ${t("unavailable")}`), h("p", { class: "hint" }, t("elevationNote")));
    }
    // Checks
    const list = view.issues;
    replace(checkPanel, list.length
      ? h("ul", { class: "issues" }, ...list.map((i) => h("li", { class: i.severity },
        h("button", { type: "button", class: "link", onclick: () => goToIssue(i) },
          h("span", { class: `badge sev-${i.severity}` }, i.severity === "error" ? "!" : "i"), " ",
          h("code", {}, i.path), " ", reasonText(i.message)))))
      : h("p", { class: "ok" }, `✓ ${t("noIssues")}`));
  }

  function goToIssue(i: Issue): void {
    const p = parsePath(i.path.split(".")[0] ?? i.path);
    if (p.list === "stops" && p.index !== undefined && view.doc.stops[p.index]) select({ type: "stop", id: view.doc.stops[p.index].id });
    else if (p.list === "routes" && p.index !== undefined && view.doc.routes[p.index]) select({ type: "route", routeId: view.doc.routes[p.index].id });
    else if (p.list === "sessions" && p.index !== undefined && view.doc.sessions[p.index]) select({ type: "session", id: view.doc.sessions[p.index].id });
    else select({ type: "event" });
    inspectorOpen = true;
    layout();
    (inspector.querySelector(".has-error input, .has-error textarea, .has-error select") as HTMLElement | null)?.focus();
  }

  // ---- keyboard -------------------------------------------------------------------------------
  const onKey = (e: KeyboardEvent) => {
    if (document.querySelector("dialog[open]")) return;
    const target = e.target as HTMLElement;
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName) && !(target as HTMLInputElement).type?.match(/radio|checkbox/);
    const key = e.key.toLowerCase();
    if (mod(e)) {
      if (key === "s") { e.preventDefault(); void run(api.save(), `${t("saveDraft")} ✓`); }
      else if (key === "z" && e.shiftKey) { e.preventDefault(); if (!view.readonly) void run(api.redo()); }
      else if (key === "z") { e.preventDefault(); if (!view.readonly) void run(api.undo()); }
      else if (key === "y") { e.preventDefault(); if (!view.readonly) void run(api.redo()); }
      else if (key === "l") { e.preventDefault(); void api.vaultLock().then(() => go("lock")); }
      return;
    }
    if (typing || view.readonly) return;
    if (target.classList.contains("maplibregl-canvas")) return; // arrows pan the map there
    if (key === "p" && activeRoute) { mode = "point"; render(); e.preventDefault(); return; }
    if (key === "d" && activeRoute) { mode = "draw"; render(); e.preventDefault(); return; }
    if (e.key === "Escape") { mode = "select"; render(); return; }
    const arrows: Record<string, [number, number]> = { ArrowUp: [0, 1], ArrowDown: [0, -1], ArrowLeft: [-1, 0], ArrowRight: [1, 0] };
    if (sel?.type === "point" || sel?.type === "stop") {
      if (e.altKey && (e.key === "ArrowUp" || e.key === "ArrowDown")) {
        e.preventDefault();
        const delta = e.key === "ArrowUp" ? -1 : 1;
        if (sel.type === "point") void edit({ op: "reorderPoint", routeId: sel.routeId, index: sel.index, delta });
        else void edit({ op: "reorderStop", id: sel.id, delta });
        return;
      }
      const a = arrows[e.key];
      if (a && !target.closest(".outline, .inspector, .drawer, .titlebar, .map-toolbar")) {
        e.preventDefault();
        const step = e.shiftKey ? 100 : 10;
        if (sel.type === "point") void edit({ op: "nudgePoint", routeId: sel.routeId, index: sel.index, eastM: a[0] * step, northM: a[1] * step });
        else void edit({ op: "nudgeStop", id: sel.id, eastM: a[0] * step, northM: a[1] * step });
        return;
      }
      if (e.key === "Enter" && (target === document.body || target.closest(".maplibregl-marker"))) {
        e.preventDefault();
        if (sel.type === "point") {
          const s = sel;
          const pt = view.doc.routes.find((r) => r.id === s.routeId)?.points[s.index];
          void coordsDialog(pt).then((q) => q && edit({ op: "movePoint", routeId: s.routeId, index: s.index, ...q }));
        } else {
          const st = view.doc.stops.find((x) => x.id === (sel as { id: string }).id);
          if (st) void coordsDialog(st).then((q) => q && edit({ op: "updateStop", stop: { ...st, ...q } }));
        }
        return;
      }
      if ((e.key === "Delete" || e.key === "Backspace") && sel.type === "point" && !target.closest(".outline, .inspector")) {
        e.preventDefault();
        void edit({ op: "deletePoint", routeId: sel.routeId, index: sel.index });
      }
    }
  };
  window.addEventListener("keydown", onKey);

  render();
  maybeCompute();

  return () => {
    window.removeEventListener("keydown", onKey);
    ro.disconnect();
    unwatch();
    map.destroy();
  };
});
