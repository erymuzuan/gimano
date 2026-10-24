import "./styles.css";
import { go, pollOnline, state } from "./app";
import { api } from "./api";
import "./screens/lock";
import "./screens/library";
import "./screens/workspace";
import "./screens/settings";

// Lock shortcut everywhere (⌘L / Ctrl+L); the workspace handles its own before this.
window.addEventListener("keydown", (e) => {
  const mod = state.info?.platform === "macos" ? e.metaKey : e.ctrlKey;
  if (mod && e.key.toLowerCase() === "l" && state.screen === "library") {
    e.preventDefault();
    void api.vaultLock().then(() => go("lock"));
  }
});

go("lock");
void pollOnline();
