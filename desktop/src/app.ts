// Shared app state and screen switching.
import { api, errorCode, errorMessage } from "./api";
import { clear } from "./dom";
import { setLang, t } from "./i18n";
import { toast } from "./ui";
import type { AppInfo, DocView } from "./types";

export type Screen = "lock" | "library" | "workspace" | "settings";

interface AppState {
  info: AppInfo | null;
  screen: Screen;
  online: boolean;
}

export const state: AppState = { info: null, screen: "lock", online: false };

type Renderer = (root: HTMLElement, arg?: unknown) => void | (() => void);
const renderers = new Map<Screen, Renderer>();
let cleanup: (() => void) | void;

export function register(screen: Screen, r: Renderer): void {
  renderers.set(screen, r);
}

export function root(): HTMLElement {
  return document.getElementById("app") as HTMLElement;
}

export function go(screen: Screen, arg?: unknown): void {
  if (cleanup) cleanup();
  cleanup = undefined;
  state.screen = screen;
  const el = root();
  clear(el);
  el.dataset.screen = screen;
  cleanup = renderers.get(screen)?.(el, arg);
}

export async function refreshInfo(): Promise<AppInfo> {
  const info = await api.appInfo();
  state.info = info;
  setLang(info.settings.lang);
  return info;
}

export function fail(e: unknown): void {
  const code = errorCode(e);
  if (code === "locked") {
    go("lock");
    return;
  }
  toast(`${t("error")}: ${errorMessage(e)}`, "error");
}

export async function openView(p: Promise<DocView>): Promise<void> {
  try {
    const v = await p;
    go("workspace", v);
  } catch (e) {
    fail(e);
  }
}

let onlineTimer: number | undefined;
const onlineListeners = new Set<(on: boolean) => void>();

export function watchOnline(fn: (on: boolean) => void): () => void {
  onlineListeners.add(fn);
  fn(state.online);
  return () => onlineListeners.delete(fn);
}

export async function pollOnline(): Promise<void> {
  try {
    state.online = await api.checkOnline();
  } catch {
    state.online = false;
  }
  for (const f of onlineListeners) f(state.online);
  window.clearTimeout(onlineTimer);
  onlineTimer = window.setTimeout(pollOnline, 60_000);
}

export const isMac = () => state.info?.platform === "macos";
export const mod = (e: KeyboardEvent) => (isMac() ? e.metaKey : e.ctrlKey);
export const modLabel = () => (isMac() ? "⌘" : "Ctrl+");
