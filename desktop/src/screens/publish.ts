// Publish / export: phone-sized guest preview of exactly what goes in the pack, change summary,
// link (default) or file + secret code. Links and codes are copied from Rust; the webview never
// receives the key unless the organizer presses "Tunjuk kod".
import { api, errorMessage } from "../api";
import { fail } from "../app";
import { h } from "../dom";
import { distance, duration, when } from "../format";
import { kindName, modeName, reasonText, t, templateName } from "../i18n";
import { button, modal, toast } from "../ui";
import type { GuestEvent, PublishPreview } from "../types";
import { tabList } from "./importer";

function guestPhone(ev: GuestEvent): HTMLElement {
  const stopName = (id?: string) => ev.stops.find((s) => s.id === id)?.label ?? "";
  return h("div", { class: "phone", role: "region", "aria-label": t("guestPreview") },
    h("div", { class: "phone-screen" },
      h("p", { class: "phone-kicker" }, templateName(ev.template)),
      h("h3", {}, ev.title),
      ev.organizer?.alias ? h("p", { class: "muted" }, ev.organizer.alias) : null,
      h("p", {}, `${when(ev.start)} – ${when(ev.end)}`),
      ev.description ? h("p", { class: "phone-desc" }, ev.description) : null,
      h("h4", {}, t("stopsH")),
      h("ol", { class: "phone-stops" }, ...ev.stops.map((s) => h("li", {},
        h("strong", {}, s.label), h("span", { class: "muted" }, ` · ${kindName(s.kind)}`),
        s.arrive ? h("div", { class: "small" }, `${when(s.arrive.from)} – ${when(s.arrive.to)}`) : null,
        s.dwellMin ? h("div", { class: "small" }, `${s.dwellMin} min`) : null,
        s.entrance ? h("div", { class: "small" }, `${t("entrance")} ✓`) : null,
        s.notes ? h("div", { class: "small phone-note" }, s.notes) : null,
        s.audience ? h("div", { class: "small muted" }, s.audience) : null,
      ))),
      ev.sessions?.length ? [h("h4", {}, t("sessionsH")), h("ul", { class: "phone-sessions" }, ...ev.sessions.map((s) =>
        h("li", {}, `${when(s.start).slice(-5)} ${s.title}${s.stopId ? ` · ${stopName(s.stopId)}` : ""}`)))] : null,
      ev.routes?.length ? [h("h4", {}, t("routesH")), h("ul", {}, ...ev.routes.map((r) =>
        h("li", {}, `${r.title ?? t("route")} · ${modeName(r.mode)} · ${distance(r.distanceM)}${r.durationS ? ` · ${duration(r.durationS)}` : ""}${r.source !== "routed" ? ` · ${t("sketchLabel")}` : ""}`)))] : null,
      h("p", { class: "small muted" }, `${t("expiresAt").split(" (")[0]}: ${when(ev.expiresAt)}`),
    ),
  );
}

export function openPublishDialog(onPublished: () => void): void {
  const m = modal(t("publish"), { wide: true });
  m.body.append(h("p", { "aria-busy": "true" }, "…"));
  let stopsOnly = false;

  const render = (p: PublishPreview) => {
    if (!p.ok || !p.event) {
      m.body.replaceChildren(
        h("p", { class: "notice warn" }, t("cannotPublish")),
        h("ul", { class: "issues" }, ...p.issues.filter((i) => i.severity === "error").map((i) =>
          h("li", {}, h("code", {}, i.path), " ", reasonText(i.message)))),
      );
      return;
    }
    const ev = p.event;
    const changes = p.previousRev
      ? p.reused
        ? h("p", {}, t("noChanges"))
        : h("div", {}, h("h3", {}, t("changes", { n: p.previousRev })), h("ul", { class: "changes" },
          ...p.changes.map((c) => h("li", { class: `chg-${c.kind}` }, `${c.kind === "added" ? "+" : c.kind === "removed" ? "−" : "~"} ${c.what}: ${c.label}${c.fields.length ? ` (${c.fields.join(", ")})` : ""}`))))
      : h("p", {}, t("firstRev"));
    const tooLong = p.fragmentLen > p.fragmentMax;
    const status = h("div", { "aria-live": "polite" });
    const stopsBox = h("input", { type: "checkbox", checked: stopsOnly, onchange: (e: Event) => {
      stopsOnly = (e.target as HTMLInputElement).checked;
      void load();
    } });
    const copyBtn = button(t("copyLink"), async () => {
      try {
        const r = await api.publishLink(stopsOnly);
        status.replaceChildren(h("p", { class: "notice ok" }, t("copied", { n: r.rev })));
        onPublished();
      } catch (e) { status.replaceChildren(h("p", { class: "error" }, errorMessage(e))); }
    }, "primary");
    copyBtn.disabled = tooLong;
    const linkPanel = h("section", { class: "stack" },
      h("p", { class: tooLong ? "error" : "muted" }, t("fragment", { n: p.fragmentLen.toLocaleString(), m: p.fragmentMax.toLocaleString() })),
      h("meter", { min: 0, max: p.fragmentMax, value: Math.min(p.fragmentLen, p.fragmentMax), high: p.fragmentMax * 0.8, "aria-label": t("fragment", { n: p.fragmentLen, m: p.fragmentMax }) }),
      tooLong || stopsOnly ? h("p", { class: "notice warn" }, t("tooLong")) : null,
      (ev.routes?.length || stopsOnly) ? h("label", { class: "check" }, stopsBox, " ", t("stopsOnly")) : null,
      copyBtn,
    );
    const codeOut = h("p", { class: "code secret", "aria-live": "polite" }, "•••• •••• ••••");
    const filePanel = h("section", { class: "stack" },
      h("p", { class: "muted" }, `${Math.ceil(p.fileBytes / 1024)} KB · .manotu-event`),
      button(t("saveFile"), async () => {
        try {
          const r = await api.publishFile();
          if (r.fileName) {
            status.replaceChildren(h("p", { class: "notice ok" }, t("saved", { f: r.fileName })));
            onPublished();
          }
        } catch (e) { status.replaceChildren(h("p", { class: "error" }, errorMessage(e))); }
      }, "primary"),
      h("div", { class: "safety" }, h("p", { class: "label" }, t("secretCode")), codeOut,
        h("div", { class: "row" },
          button(t("showCode"), async () => {
            try { codeOut.textContent = await api.secretCodeReveal(); } catch (e) { fail(e); }
          }, "ghost"),
          button(t("copyCode"), async () => {
            try { await api.secretCodeCopy(); toast(`${t("secretCode")} ✓`, "ok"); } catch (e) { fail(e); }
          }),
        ),
      ),
    );
    const tabs = tabList([t("link"), t("file")], [linkPanel, filePanel], undefined, tooLong && !stopsOnly ? 1 : 0);
    const warnings = p.issues.filter((i) => i.severity === "warning");
    m.body.replaceChildren(
      h("div", { class: "publish" },
        h("div", {}, h("h3", {}, t("guestPreview")), guestPhone(ev), h("p", { class: "small muted" }, t("orgNoteExcluded")),
          h("details", {}, h("summary", {}, t("exactJson")), h("pre", { class: "json" }, JSON.stringify(ev, null, 2)))),
        h("div", { class: "stack" },
          h("p", { class: "rev" }, `v${p.rev}`),
          changes,
          warnings.length ? h("ul", { class: "issues warn" }, ...warnings.map((i) => h("li", {}, reasonText(i.message)))) : null,
          tabs, linkPanel, filePanel, status,
          h("p", { class: "notice" }, `🔒 ${t("nothingSent")}`),
        ),
      ),
    );
  };
  const load = async () => {
    try { render(await api.publishPreview(stopsOnly)); } catch (e) { fail(e); m.close(); }
  };
  m.footer.append(button(t("close"), () => m.close(), "ghost"));
  void load();
}
