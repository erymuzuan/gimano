// Import dialog (pack link / .manotu-event + secret code / GPX), GPX import preview and the
// GPX export confirmation (plaintext warning).
import { api, errorCode, errorMessage } from "../api";
import { fail, go } from "../app";
import { field, h, svg, uid } from "../dom";
import { distance, when } from "../format";
import { type Key, modeName, t } from "../i18n";
import { button, modal, toast } from "../ui";
import type { DocView, GpxExportRequest, GpxPreview, ImportPreview, TravelMode } from "../types";

function previewBox(p: ImportPreview, onImported: () => void, close: () => void): HTMLElement {
  const decisionKey = `dec_${p.decision}` as Key;
  const box = h("div", { class: "stack import-preview" },
    // "upgraded" (laluan penuh ditambah) was applied already: it is good news, not a warning.
    h("p", { class: p.canImport || p.decision === "upgraded" ? "notice ok" : "notice warn" }, t(decisionKey)),
    p.stopsOnly ? h("p", { class: "notice" }, t("importStopsOnly")) : null,
    h("dl", { class: "kv" },
      h("dt", {}, t("title")), h("dd", {}, p.title),
      p.alias ? [h("dt", {}, t("alias")), h("dd", {}, p.alias)] : null,
      h("dt", {}, t("start")), h("dd", {}, when(p.start)),
      h("dt", {}, "Rev"), h("dd", {}, String(p.rev)),
      h("dt", {}, t("stopsH")), h("dd", {}, String(p.stops)),
      h("dt", {}, t("routesH")), h("dd", {}, String(p.routes)),
    ),
    p.parentCheck && p.parentCheck !== "matches" ? h("p", { class: "notice warn" }, t(`parent_${p.parentCheck}` as Key)) : null,
    h("div", { class: "safety" },
      h("p", { class: "label" }, t("safetyCode")),
      h("p", { class: "code", "aria-label": `${t("safetyCode")}: ${p.safetyCode.split("").join(" ")}` }, p.safetyCode),
      h("p", { class: "hint" }, t("safetyHint")),
    ),
  );
  if (p.canImport) {
    box.append(button(t("importDo"), async () => {
      try {
        await api.importConfirm();
        toast(`${t("importDo")} ✓ ${p.title}`, "ok");
        close();
        onImported();
      } catch (e) { fail(e); }
    }, "primary"));
  }
  return box;
}

export function openImportDialog(onImported: () => void): void {
  const m = modal(t("importTitle"), { wide: true });
  const out = h("div", { "aria-live": "polite" });
  const showError = (e: unknown) => {
    const code = errorCode(e);
    // Short links (§5.6) add drop_gone (404/410) and offline/server (retry with the same button).
    const text = code === "invalid_secret_code" ? errorMessage(e)
      : code === "drop_gone" ? t("dropGone")
      : code === "offline" || code === "server" ? t("dropOffline")
      : t("cannotOpen");
    out.replaceChildren(h("p", { class: "error", role: "alert" }, text));
  };

  const linkInput = h("textarea", { class: "input", rows: 3, spellcheck: "false", autocomplete: "off" });
  const linkPanel = h("section", { class: "stack" },
    field(t("paste"), linkInput),
    button(t("check"), async () => {
      try { out.replaceChildren(previewBox(await api.importLinkPreview(linkInput.value.trim()), onImported, m.close)); }
      catch (e) { showError(e); }
    }, "primary"),
  );

  // 8-character codes (XXXX-XXXX) and the 52-character codes of older files both work (§5.2).
  const codeInput = h("input", { class: "input mono", type: "text", spellcheck: "false", autocomplete: "off", autocapitalize: "characters", placeholder: "XXXX-XXXX" });
  const fileLabel = h("span", { class: "muted" });
  const codeField = field(t("secretCodeInput"), codeInput);
  let needsCode = true;
  const filePanel = h("section", { class: "stack" },
    h("div", { class: "row" },
      button(t("pickFile"), async () => {
        try {
          const f = await api.importFilePick();
          if (f) {
            fileLabel.textContent = `${f.fileName} · ${Math.ceil(f.size / 1024)} KB`;
            // Suite-3 files carry their key (§5.2): no code to ask for.
            needsCode = f.needsCode;
            codeField.hidden = !needsCode;
            if (!needsCode) out.replaceChildren(previewBox(await api.importFilePreview(""), onImported, m.close));
          }
        } catch (e) { showError(e); }
      }),
      fileLabel,
    ),
    codeField,
    button(t("check"), async () => {
      try { out.replaceChildren(previewBox(await api.importFilePreview(needsCode ? codeInput.value : ""), onImported, m.close)); }
      catch (e) { showError(e); }
    }, "primary"),
  );

  const gpxPanel = h("section", { class: "stack" },
    h("p", {}, "GPX → " + t("newRoute")),
    button(t("pickFile"), async () => {
      try {
        const p = await api.gpxPick();
        if (p) { m.close(); openGpxDialog(p, (v) => go("workspace", v)); }
      } catch (e) { fail(e); }
    }, "primary"),
  );

  const panels = [linkPanel, filePanel, gpxPanel];
  const labels = [t("importLink"), t("importFile"), t("importGpx")];
  const tabs = tabList(labels, panels, () => out.replaceChildren());
  m.body.append(tabs, ...panels, out);
  m.footer.append(button(t("close"), () => m.close(), "ghost"));
  linkInput.focus();
}

/** Accessible tabs (arrow keys move between tabs). */
export function tabList(labels: string[], panels: HTMLElement[], onChange?: (i: number) => void, initial = 0): HTMLElement {
  const ids = labels.map(() => uid("tab"));
  const tabs = labels.map((l, i) => h("button", {
    role: "tab", type: "button", id: ids[i], class: "tab",
    "aria-controls": (panels[i].id ||= uid("panel")),
  }, l));
  const select = (i: number, focus = false) => {
    tabs.forEach((tb, j) => {
      tb.setAttribute("aria-selected", String(i === j));
      tb.tabIndex = i === j ? 0 : -1;
      panels[j].hidden = i !== j;
      panels[j].setAttribute("role", "tabpanel");
      panels[j].setAttribute("aria-labelledby", ids[j]);
    });
    if (focus) tabs[i].focus();
    onChange?.(i);
  };
  tabs.forEach((tb, i) => {
    tb.addEventListener("click", () => select(i));
    tb.addEventListener("keydown", (e) => {
      if (e.key === "ArrowRight") select((i + 1) % tabs.length, true);
      if (e.key === "ArrowLeft") select((i - 1 + tabs.length) % tabs.length, true);
    });
  });
  const list = h("div", { role: "tablist", class: "tabs" }, ...tabs);
  select(initial);
  return list;
}

function gpxPreviewSvg(points: [number, number][]): SVGElement {
  const w = 420, ht = 220, pad = 12;
  const s = svg("svg", { viewBox: `0 0 ${w} ${ht}`, class: "gpx-preview", role: "img", "aria-label": "GPX" });
  if (points.length < 2) return s;
  const xs = points.map((p) => p[0]), ys = points.map((p) => p[1]);
  const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const k = Math.min((w - 2 * pad) / Math.max(x1 - x0, 1e-6), (ht - 2 * pad) / Math.max(y1 - y0, 1e-6));
  const d = points.map((p, i) => `${i ? "L" : "M"}${(pad + (p[0] - x0) * k).toFixed(1)},${(ht - pad - (p[1] - y0) * k).toFixed(1)}`).join(" ");
  s.appendChild(svg("path", { d, fill: "none", stroke: "#0047AB", "stroke-width": 2.5 }));
  return s;
}

export function openGpxDialog(p: GpxPreview, done: (v: DocView) => void): void {
  const m = modal(t("gpxTitle"), { wide: true });
  if (!p.paths.length) {
    m.body.append(h("p", { class: "error" }, "GPX: 0 trk/rte"));
    m.footer.append(button(t("close"), () => m.close()));
    return;
  }
  let index = 0;
  const pic = h("div", {});
  const name = `gpx-path-${uid()}`;
  const choices = h("fieldset", { class: "stack" }, h("legend", {}, `${t("track")} / ${t("gpxRoute")}`),
    ...p.paths.map((path, i) => h("label", { class: "radio" },
      h("input", { type: "radio", name, checked: i === 0, onchange: () => { index = i; draw(); } }),
      ` ${path.kind === "track" ? t("track") : t("gpxRoute")}: ${path.name ?? "—"} · ${t("points", { n: path.pointCount })} · ${distance(path.distanceM)}`,
      path.hasElevation ? ` · ${t("hasEle")}` : "",
      path.hasTimes ? ` · ${t("hasTime")}` : "",
    )),
  );
  const draw = () => pic.replaceChildren(gpxPreviewSvg(p.paths[index].preview));
  draw();
  const snapName = `snap-${uid()}`;
  const snap = h("input", { type: "radio", name: snapName, value: "snap" });
  const keep = h("input", { type: "radio", name: snapName, value: "keep", checked: true });
  const strip = h("input", { type: "checkbox", checked: true });
  const wpts = h("input", { type: "checkbox", checked: p.waypoints.length > 0 && p.waypoints.length <= 50 });
  const mode = h("select", { class: "input" }, ...(["bicycle", "motorcycle", "car", "walk"] as TravelMode[]).map((x) => h("option", { value: x }, modeName(x))));
  m.body.append(
    h("div", { class: "split" }, pic, h("div", { class: "stack" },
      choices,
      h("fieldset", { class: "stack" }, h("legend", {}, t("followRoads")),
        h("label", { class: "radio" }, snap, " ", t("snap")),
        h("label", { class: "radio" }, keep, " ", t("keepAsSketch")),
      ),
      field(t("mode"), mode),
      h("label", { class: "check" }, strip, " ", t("stripTimes")),
      p.waypoints.length ? h("label", { class: "check" }, wpts, " ", t("waypointsAsStops"), ` (${p.waypoints.length})`) : null,
      p.hasElevation ? null : h("p", { class: "hint" }, `${t("ascent")}/${t("descent")}: ${t("unavailable")}`),
    )),
  );
  m.footer.append(
    button(t("cancel"), () => m.close(), "ghost"),
    button(t("apply"), async () => {
      try {
        const v = await api.gpxApply({
          pathIndex: index, mode: mode.value as TravelMode, snap: snap.checked,
          stripTimestamps: strip.checked, waypointsAsStops: wpts.checked && p.waypoints.length > 0,
        });
        m.close();
        done(v);
      } catch (e) { fail(e); }
    }, "primary"),
  );
}

export function openGpxExport(routeId: string): void {
  const m = modal(t("gpxExportTitle"));
  const stops = h("input", { type: "checkbox", checked: true });
  const ele = h("input", { type: "checkbox", checked: true });
  const ack = h("input", { type: "checkbox" });
  const manifest = h("div", { "aria-live": "polite" });
  const req = (): GpxExportRequest => ({
    acknowledgedPlaintext: ack.checked, includeStops: stops.checked, includeElevation: ele.checked, includeTimestamps: false,
  });
  const exportBtn = button(t("exportDo"), async () => {
    try {
      const name = await api.gpxExport(routeId, req());
      if (name) { toast(name, "ok"); m.close(); }
    } catch (e) { toast(errorMessage(e), "error"); }
  }, "danger");
  exportBtn.disabled = true;
  const refresh = async () => {
    exportBtn.disabled = !ack.checked;
    try {
      const mf = await api.gpxExportManifest(routeId, req());
      manifest.replaceChildren(h("p", {}, t("gpxWillContain")), h("ul", {},
        h("li", {}, `${t("route")}: ${mf.trackName ?? "—"} · ${t("points", { n: mf.pointCount })}`),
        ...mf.waypointNames.map((n) => h("li", {}, n)),
        h("li", {}, `${t("gpxEle")}: ${mf.hasElevation ? "✓" : "—"}`),
      ));
    } catch (e) { fail(e); }
  };
  for (const c of [stops, ele, ack]) c.addEventListener("change", () => void refresh());
  m.body.append(
    h("p", { class: "notice warn", role: "note" }, t("gpxWarning")),
    h("label", { class: "check" }, stops, " ", t("gpxStops")),
    h("label", { class: "check" }, ele, " ", t("gpxEle")),
    manifest,
    h("label", { class: "check strong" }, ack, " ", t("gpxAck")),
  );
  m.footer.append(button(t("cancel"), () => m.close(), "ghost"), exportBtn);
  void refresh();
}
