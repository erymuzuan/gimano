// Manotu short-link viewer configuration (/j/, docs/events.md §5.6). The page sends only the dropId to the API
// (GET <apiBaseUrl>/api/v1/drops/<dropId>); the secret after `#` never leaves the browser. The API host must be in
// connect-src in index.html. Dev: open the page over http and add ?api=http://host:5080 (it must also be in the CSP).
window.MANOTU_EVENT_CONFIG = {
  apiBaseUrl: "https://gimano-api.bukitbunga.com",
  styleUrl: "https://tiles.openfreemap.org/styles/positron",
  // Custom scheme the apps register (docs/events.md §5.3, §5.6).
  appScheme: "manotu",
  // The short link "Dapatkan Manotu" copies before going to the store, so the app can offer it after install.
  linkSite: "https://manotu.app",
  playUrl: "https://play.google.com/store/apps/details?id=my.manotu.app&gl=MY",
  // Set when Manotu is listed on the App Store; until then iOS goes to downloadUrl.
  appStoreUrl: "",
  downloadUrl: "https://manotu.app/download/"
};
