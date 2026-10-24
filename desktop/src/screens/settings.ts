// Settings: API base URL (+ presets), online tiles, language, invitation site, key vault,
// recovery file, delete all local data.
import { api, errorMessage } from "../api";
import { fail, go, refreshInfo, register, state } from "../app";
import { field, h, uid } from "../dom";
import { t } from "../i18n";
import { button, modal, toast } from "../ui";
import type { Lang, Settings } from "../types";

function passphrasePrompt(title: string, twice: boolean): Promise<string | null> {
  return new Promise((resolve) => {
    let result: string | null = null;
    const m = modal(title, { onClose: () => resolve(result) });
    const a = h("input", { type: "password", class: "input", autocomplete: "new-password" });
    const b = h("input", { type: "password", class: "input", autocomplete: "new-password" });
    const err = h("p", { class: "error", role: "alert" });
    m.body.append(h("p", { class: "hint" }, t("recoveryHint")), field(t("passphrase"), a), ...(twice ? [field(t("passphraseAgain"), b)] : []), err);
    m.footer.append(button(t("cancel"), () => m.close(), "ghost"), button(t("ok"), () => {
      if (a.value.length < 10) { err.textContent = t("passphrase"); return; }
      if (twice && a.value !== b.value) { err.textContent = t("mismatch"); return; }
      result = a.value;
      m.close();
    }, "primary"));
    a.focus();
  });
}

register("settings", (root) => {
  const info = state.info;
  if (!info) { go("lock"); return; }
  const s: Settings = { ...info.settings };
  const unlocked = info.vault.state === "unlocked";

  const api_ = h("input", { class: "input", type: "url", value: s.apiBaseUrl, spellcheck: "false" });
  const listId = uid("presets");
  api_.setAttribute("list", listId);
  const presets = h("datalist", { id: listId }, ...info.apiPresets.map(([n, u]) => h("option", { value: u }, n)));
  const presetButtons = h("div", { class: "row" }, ...info.apiPresets.map(([n, u]) => button(n, () => { api_.value = u; }, "ghost")));
  const tiles = h("input", { type: "checkbox", checked: s.tilesOnline });
  const lang = h("select", { class: "input" }, h("option", { value: "ms" }, "Bahasa Melayu"), h("option", { value: "en" }, "English"));
  lang.value = s.lang;
  const site = h("input", { class: "input", type: "url", value: s.inviteSite, spellcheck: "false" });
  const err = h("p", { class: "error", role: "alert" });
  const del = h("input", { class: "input", type: "text", autocomplete: "off" });

  const save = async () => {
    err.textContent = "";
    try {
      await api.settingsSet({ apiBaseUrl: api_.value, tilesOnline: tiles.checked, lang: lang.value as Lang, inviteSite: site.value });
      await refreshInfo();
      toast(t("settingsSaved"), "ok");
      go("settings");
    } catch (e) { err.textContent = errorMessage(e); }
  };

  root.append(
    h("header", { class: "titlebar" },
      h("nav", { class: "breadcrumb", "aria-label": "breadcrumb" },
        h("button", { type: "button", class: "link", onclick: () => go(unlocked ? "library" : "lock") }, unlocked ? t("library") : t("unlock")),
        h("span", { "aria-hidden": "true" }, " / "), h("span", { "aria-current": "page" }, t("settingsTitle"))),
    ),
    h("main", { class: "settings" },
      h("h1", {}, t("settingsTitle")),
      h("section", { class: "stack" },
        field(t("apiUrl"), api_, { hint: t("apiHint") }), presets, presetButtons,
        h("label", { class: "check" }, tiles, " ", t("tiles")),
        h("p", { class: "hint" }, t("tilesHint")),
        h("p", { class: "hint" }, `${info.styleUrl} · ${t("offlineMaps")}`),
        field(t("language"), lang),
        field(t("inviteSite"), site),
        err,
        button(t("saveSettings"), () => void save(), "primary"),
      ),
      h("section", { class: "stack" },
        h("h2", {}, t("vault")),
        h("p", {}, info.vault.mode === "passphrase" ? t("vaultPassphrase") : info.vault.mode === "keychain" ? t("vaultKeychain") : "—"),
        unlocked ? [
          h("h3", {}, t("recovery")), h("p", { class: "hint" }, t("recoveryHint")),
          h("div", { class: "row" },
            button(t("recoveryExport"), async () => {
              const p = await passphrasePrompt(t("recoveryExport"), true);
              if (!p) return;
              try { const f = await api.recoveryExport(p); if (f) toast(f, "ok"); } catch (e) { fail(e); }
            }),
            button(t("recoveryImport"), async () => {
              const p = await passphrasePrompt(t("recoveryImport"), false);
              if (!p) return;
              try { const n = await api.recoveryImport(p); if (n !== null) toast(`+${n}`, "ok"); } catch (e) { fail(e); }
            }),
          ),
        ] : null,
      ),
      h("section", { class: "stack danger-zone" },
        h("h2", {}, t("deleteAll")),
        field(t("deleteAllHint"), del),
        button(t("deleteAll"), async () => {
          try {
            await api.deleteAll(del.value);
            await refreshInfo();
            go("lock");
          } catch (e) { toast(errorMessage(e), "error"); }
        }, "danger"),
      ),
      h("p", { class: "muted small" }, t("version", { v: info.version })),
    ),
  );
});
