import { defineConfig } from "vitest/config";

// Tauri expects a fixed dev port and a relative, self-contained build (no CDN, no remote code).
export default defineConfig({
  clearScreen: false,
  server: { port: 1420, strictPort: true, host: "localhost" },
  envPrefix: ["VITE_", "TAURI_ENV_"],
  // MapLibre's worker is bundled as an ES module worker served from 'self' (CSP worker-src).
  worker: { format: "es" },
  build: {
    target: "es2022",
    sourcemap: false,
    assetsInlineLimit: 0,
    chunkSizeWarningLimit: 2000,
  },
  test: {
    include: ["src/**/*.test.ts"],
    environment: "node",
  },
});
