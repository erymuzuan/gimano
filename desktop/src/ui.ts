// Modal dialogs (native <dialog> + showModal: focus trap, Esc to close), toasts and small prompts.
import { clear, h, uid } from "./dom";
import { t } from "./i18n";
import { parseCoords } from "./format";
import type { LatLng } from "./types";

export interface Modal {
  dialog: HTMLDialogElement;
  body: HTMLElement;
  footer: HTMLElement;
  close(): void;
}

export function modal(title: string, opts: { wide?: boolean; onClose?: () => void } = {}): Modal {
  const titleId = uid("dlg");
  const body = h("div", { class: "dialog-body" });
  const footer = h("div", { class: "dialog-footer" });
  const dialog = h(
    "dialog",
    { class: opts.wide ? "modal wide" : "modal", "aria-labelledby": titleId },
    h("header", { class: "dialog-header" },
      h("h2", { id: titleId }, title),
      h("button", { class: "icon-btn", type: "button", "aria-label": t("close"), onclick: () => close() }, "×"),
    ),
    body,
    footer,
  );
  const close = () => {
    if (dialog.open) dialog.close();
  };
  dialog.addEventListener("close", () => {
    dialog.remove();
    opts.onClose?.();
  });
  document.body.appendChild(dialog);
  dialog.showModal();
  return { dialog, body, footer, close };
}

export function button(label: string, onclick: () => void, kind: "primary" | "secondary" | "danger" | "ghost" = "secondary", extra: Record<string, string> = {}): HTMLButtonElement {
  return h("button", { type: "button", class: `btn ${kind}`, onclick, ...extra }, label);
}

let toastHost: HTMLElement | null = null;

export function toast(message: string, kind: "info" | "error" | "ok" = "info"): void {
  if (!toastHost) {
    toastHost = h("div", { class: "toasts", role: "status", "aria-live": "polite" });
    document.body.appendChild(toastHost);
  }
  const el = h("div", { class: `toast ${kind}` }, message);
  toastHost.appendChild(el);
  setTimeout(() => el.remove(), kind === "error" ? 8000 : 5000);
}

export function confirmDialog(message: string, okLabel: string, danger = false): Promise<boolean> {
  return new Promise((resolve) => {
    let result = false;
    const m = modal(okLabel, { onClose: () => resolve(result) });
    m.body.appendChild(h("p", {}, message));
    m.footer.append(
      button(t("cancel"), () => m.close(), "ghost"),
      button(okLabel, () => { result = true; m.close(); }, danger ? "danger" : "primary"),
    );
  });
}

/** "Enter: type coordinates" — the keyboard alternative to placing or dragging on the map. */
export function coordsDialog(initial?: LatLng): Promise<LatLng | null> {
  return new Promise((resolve) => {
    let result: LatLng | null = null;
    const m = modal(t("coordsTitle"), { onClose: () => resolve(result) });
    const input = h("input", {
      type: "text", class: "input", value: initial ? `${initial.lat.toFixed(6)}, ${initial.lng.toFixed(6)}` : "",
      "aria-describedby": "coords-hint", autocomplete: "off", spellcheck: "false",
    });
    const err = h("p", { class: "error", role: "alert" });
    const submit = () => {
      const p = parseCoords(input.value);
      if (!p) { err.textContent = t("coordsBad"); input.setAttribute("aria-invalid", "true"); return; }
      result = p;
      m.close();
    };
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); submit(); } });
    m.body.append(h("label", { for: (input.id = uid()) }, t("pin")), input, h("p", { class: "hint", id: "coords-hint" }, t("coordsHint")), err);
    m.footer.append(button(t("cancel"), () => m.close(), "ghost"), button(t("ok"), submit, "primary"));
    input.select();
  });
}

export function busy<T>(el: HTMLElement, p: Promise<T>): Promise<T> {
  el.setAttribute("aria-busy", "true");
  return p.finally(() => el.removeAttribute("aria-busy"));
}

export function replace(el: Element, ...nodes: (Node | null | false | undefined)[]): void {
  clear(el);
  el.append(...nodes.filter((n): n is Node => !!n));
}
