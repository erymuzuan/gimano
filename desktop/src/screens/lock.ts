// First-run vault setup and unlock. No plaintext fallback: without a keychain, a passphrase.
import { api, errorCode, errorMessage } from "../api";
import { fail, go, refreshInfo, register } from "../app";
import { field, h } from "../dom";
import { t } from "../i18n";
import { button, toast } from "../ui";

function passphraseForm(confirmTwice: boolean, submitLabel: string, onSubmit: (p: string) => Promise<void>): HTMLElement {
  const p1 = h("input", { type: "password", class: "input", autocomplete: "new-password", minlength: 10 });
  const p2 = h("input", { type: "password", class: "input", autocomplete: "new-password" });
  const err = h("p", { class: "error", role: "alert" });
  const submitBtn = button(submitLabel, () => void submit(), "primary");
  const submit = async () => {
    err.textContent = "";
    if (confirmTwice && p1.value !== p2.value) { err.textContent = t("mismatch"); return; }
    if (p1.value.length < 10) { err.textContent = t("passphrase"); return; }
    submitBtn.disabled = true;
    try {
      await onSubmit(p1.value);
    } catch (e) {
      err.textContent = errorCode(e) === "wrong_key" ? t("wrongKey") : errorMessage(e);
    } finally {
      submitBtn.disabled = false;
    }
  };
  const form = h("form", { class: "stack", onsubmit: (e: Event) => { e.preventDefault(); void submit(); } },
    field(t("passphrase"), p1),
    confirmTwice ? field(t("passphraseAgain"), p2) : null,
    err,
    submitBtn,
  );
  submitBtn.type = "submit";
  queueMicrotask(() => p1.focus());
  return form;
}

async function afterUnlock(purged: number): Promise<void> {
  await refreshInfo();
  if (purged > 0) toast(t("purged", { n: purged }), "info");
  go("library");
}

register("lock", (root) => {
  const card = h("main", { class: "lock-card", "aria-labelledby": "lock-title" });
  root.append(h("div", { class: "lock-screen" }, card));
  void (async () => {
    const info = await refreshInfo();
    const v = info.vault;
    const logo = h("img", { src: "/icon.svg", alt: "", width: 72, height: 72 });
    if (v.state === "unlocked") { go("library"); return; }
    if (v.state === "uninitialized") {
      const box = h("div", { class: "stack" });
      const usePass = () => {
        box.replaceChildren(passphraseForm(true, t("setupPassphrase"), async (p) => {
          await api.vaultSetup(p);
          await afterUnlock(0);
        }));
      };
      const keychain = button(t("setupKeychain"), async () => {
        keychain.disabled = true;
        try {
          await api.vaultSetup();
          await afterUnlock(0);
        } catch (e) {
          if (errorCode(e) === "keychain_unavailable") {
            box.prepend(h("p", { class: "notice warn" }, t("noKeychain")));
            usePass();
          } else fail(e);
        } finally {
          keychain.disabled = false;
        }
      }, "primary");
      box.append(keychain, button(t("setupPassphrase"), usePass, "ghost"));
      card.append(logo, h("h1", { id: "lock-title" }, t("welcome")), h("p", {}, t("setupHint")), h("p", { class: "muted" }, t("localOnly")), box);
      keychain.focus();
      return;
    }
    card.append(logo, h("h1", { id: "lock-title" }, t("locked")));
    if (v.mode === "passphrase") {
      card.append(passphraseForm(false, t("unlock"), async (p) => afterUnlock(await api.vaultUnlock(p))));
    } else {
      const msg = h("p", { class: "error", role: "alert" });
      const b = button(t("unlock"), async () => {
        try {
          await afterUnlock(await api.vaultUnlock());
        } catch (e) {
          msg.textContent = errorCode(e) === "keychain_key_missing" ? t("keyMissing") : errorMessage(e);
        }
      }, "primary");
      card.append(b, msg, button(t("settings"), () => go("settings"), "ghost"));
      // Keychain vaults unlock without typing; the OS may ask for permission.
      b.click();
    }
  })();
});
