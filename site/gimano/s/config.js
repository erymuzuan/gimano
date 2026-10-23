// Gimano share viewer configuration. The API must be served over HTTPS for the public page
// (browsers block an http:// API from an https:// page). Dev: open the page over http and use ?api=…
window.GIMANO_SHARE_CONFIG = {
  apiBaseUrl: "https://gimano-api.bukitbunga.com",
  styleUrl: "https://tiles.openfreemap.org/styles/positron",
  pollSeconds: 10
};
