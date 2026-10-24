// Manotu event viewer configuration. The page makes no API calls: the plan travels in the link fragment
// (docs/events.md §5.1). Only the map style/tiles are fetched; keep their host in the CSP in index.html.
window.MANOTU_EVENT_CONFIG = {
  styleUrl: "https://tiles.openfreemap.org/styles/positron",
  // Custom scheme the apps register for invitations (docs/events.md §5.3).
  appScheme: "manotu",
  // Where "Belum ada Manotu?" points; relative so it works on every host of this folder.
  downloadUrl: "../download/"
};
