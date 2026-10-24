// Library: recent events/routes, templates, new/import, lock, settings.
import { api } from "../api";
import { fail, go, openView, register, state } from "../app";
import { h, svg } from "../dom";
import { distance, when } from "../format";
import { t, templateName } from "../i18n";
import { button, confirmDialog } from "../ui";
import type { DocSummary, Template } from "../types";
import { openImportDialog } from "./importer";

const TEMPLATES: Template[] = ["kenduri", "dinner", "company", "meeting", "seminar", "ride", "convoy", "trip", "picnic", "hike"];
const KL = { lat: 3.139, lng: 101.6869 };

function miniRoute(points: [number, number][]): SVGElement {
  const w = 220, hgt = 96, pad = 10;
  const s = svg("svg", { viewBox: `0 0 ${w} ${hgt}`, class: "mini-route", "aria-hidden": "true", focusable: "false" });
  if (points.length < 2) return s;
  const xs = points.map((p) => p[0]), ys = points.map((p) => p[1]);
  const [minX, maxX, minY, maxY] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const sx = (w - 2 * pad) / Math.max(maxX - minX, 1e-6);
  const sy = (hgt - 2 * pad) / Math.max(maxY - minY, 1e-6);
  const k = Math.min(sx, sy);
  const ox = (w - (maxX - minX) * k) / 2, oy = (hgt - (maxY - minY) * k) / 2;
  const d = points.map((p, i) => `${i ? "L" : "M"}${(ox + (p[0] - minX) * k).toFixed(1)},${(hgt - oy - (p[1] - minY) * k).toFixed(1)}`).join(" ");
  s.appendChild(svg("path", { d, fill: "none", stroke: "#0047AB", "stroke-width": 3, "stroke-linecap": "round", "stroke-linejoin": "round" }));
  const [fx, fy] = points[0];
  const [lx, ly] = points[points.length - 1];
  s.appendChild(svg("circle", { cx: ox + (fx - minX) * k, cy: hgt - oy - (fy - minY) * k, r: 4, fill: "#FFFFFF", stroke: "#0047AB", "stroke-width": 2 }));
  s.appendChild(svg("circle", { cx: ox + (lx - minX) * k, cy: hgt - oy - (ly - minY) * k, r: 4, fill: "#FF6B4A" }));
  return s;
}

function card(d: DocSummary, reload: () => void): HTMLElement {
  const kind = d.imported ? "imported" : d.kind;
  const status = d.imported ? t("imported") : d.publishedRev ? t("published", { n: d.publishedRev }) : t("draft");
  const meta = [
    d.kind === "event" && d.start ? when(d.start) : null,
    t("stops", { n: d.stopCount }),
    d.routeCount ? `${t("routes", { n: d.routeCount })} · ${distance(d.distanceM)}` : null,
  ].filter(Boolean).join(" · ");
  return h("li", { class: "card" },
    h("button", { class: "card-main", type: "button", onclick: () => void openView(api.open(kind, d.id)) },
      miniRoute(d.preview),
      h("span", { class: "card-title" }, d.title || t("newEvent")),
      h("span", { class: "card-meta" }, meta),
      h("span", { class: `badge ${d.imported ? "badge-import" : d.publishedRev ? "badge-pub" : ""}` }, status),
    ),
    h("button", {
      class: "icon-btn card-delete", type: "button", "aria-label": `${t("delete")}: ${d.title}`,
      onclick: async () => {
        if (await confirmDialog(t("deleteConfirm", { t: d.title }), t("delete"), true)) {
          try { await api.remove(kind, d.id); reload(); } catch (e) { fail(e); }
        }
      },
    }, "🗑"),
  );
}

register("library", (root) => {
  const list = h("ul", { class: "cards", "aria-label": t("recent") });
  const reload = async () => {
    try {
      const items = await api.library();
      list.replaceChildren(...items.map((d) => card(d, () => void reload())));
      if (!items.length) list.append(h("li", { class: "empty" }, t("empty")));
    } catch (e) { fail(e); }
  };
  const lockBtn = button(`${t("lock")}`, async () => { await api.vaultLock(); go("lock"); }, "ghost", { "aria-keyshortcuts": "Meta+L Control+L" });
  root.append(
    h("header", { class: "titlebar" },
      h("div", { class: "brand" }, h("img", { src: "/icon.svg", alt: "", width: 28, height: 28 }), h("span", {}, t("appName"))),
      h("nav", { class: "toolbar", "aria-label": t("library") },
        button(t("newEvent"), () => void openView(api.newEvent("other", KL)), "primary"),
        button(t("newRoute"), () => void openView(api.newRoute("bicycle"))),
        button(t("import"), () => openImportDialog(() => void reload())),
        lockBtn,
        button(t("settings"), () => go("settings"), "ghost"),
      ),
    ),
    h("main", { class: "library" },
      h("p", { class: "notice" }, t("localOnly")),
      h("section", { "aria-labelledby": "lib-recent" }, h("h2", { id: "lib-recent" }, t("recent")), list),
      h("section", { "aria-labelledby": "lib-tpl" },
        h("h2", { id: "lib-tpl" }, t("templates")),
        h("ul", { class: "templates" },
          ...TEMPLATES.map((tp) => h("li", {},
            h("button", { type: "button", class: `template tpl-${tp}`, onclick: () => void openView(api.newEvent(tp, KL)) },
              h("span", { class: "tpl-icon", "aria-hidden": "true" }, { kenduri: "🏠", dinner: "🍽", company: "🏢", ride: "🚲", convoy: "🏍", trip: "🧳", meeting: "🗂", seminar: "🎤", picnic: "🧺", hike: "🥾", other: "📍" }[tp]),
              h("span", {}, templateName(tp)),
            ),
          )),
        ),
      ),
      h("p", { class: "muted small" }, `Manotu Desktop ${state.info?.version ?? ""}`),
    ),
  );
  void reload();
});
