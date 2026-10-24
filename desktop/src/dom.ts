// Tiny DOM helper. Children are text or nodes; strings are always inserted as text nodes, so
// labels from imported packs or GPX names can never become markup.

type Child = Node | string | number | null | undefined | false;
type Attrs = Record<string, string | number | boolean | null | undefined | EventListener>;

export function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Attrs = {},
  ...children: (Child | Child[])[]
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith("on") && typeof v === "function") {
      el.addEventListener(k.slice(2).toLowerCase(), v as EventListener);
    } else if (k === "class") {
      el.className = String(v);
    } else if (k === "value" && "value" in el) {
      (el as HTMLInputElement).value = String(v);
    } else if (k === "checked" && "checked" in el) {
      (el as HTMLInputElement).checked = Boolean(v);
    } else if (v === true) {
      el.setAttribute(k, "");
    } else {
      el.setAttribute(k, String(v));
    }
  }
  append(el, children);
  return el;
}

function append(el: Node, children: (Child | Child[])[]): void {
  for (const c of children) {
    if (Array.isArray(c)) append(el, c);
    else if (c === null || c === undefined || c === false) continue;
    else el.appendChild(typeof c === "object" ? c : document.createTextNode(String(c)));
  }
}

export function clear(el: Element): void {
  while (el.firstChild) el.removeChild(el.firstChild);
}

export function svg(tag: string, attrs: Record<string, string | number>, ...children: SVGElement[]): SVGElement {
  const el = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, String(v));
  for (const c of children) el.appendChild(c);
  return el;
}

let idSeq = 0;
export const uid = (p = "f") => `${p}-${++idSeq}`;

/** Labelled form field; `path` ties it to validation issues (data-path). */
export function field(label: string, input: HTMLElement, opts: { hint?: string; path?: string; error?: string } = {}): HTMLElement {
  const id = input.id || uid();
  input.id = id;
  const hintId = opts.hint ? `${id}-hint` : undefined;
  const errId = opts.error ? `${id}-err` : undefined;
  const described = [hintId, errId].filter(Boolean).join(" ");
  if (described) input.setAttribute("aria-describedby", described);
  if (opts.error) input.setAttribute("aria-invalid", "true");
  return h(
    "div",
    { class: opts.error ? "field has-error" : "field", "data-path": opts.path },
    h("label", { for: id }, label),
    input,
    opts.hint ? h("p", { class: "hint", id: hintId }, opts.hint) : null,
    opts.error ? h("p", { class: "error", id: errId, role: "alert" }, opts.error) : null,
  );
}
