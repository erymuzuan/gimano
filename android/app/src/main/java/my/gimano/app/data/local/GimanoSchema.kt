package my.gimano.app.data.local

/**
 * The shared on-device schema, copied VERBATIM from docs/privacy-and-backup.md so a backup made on
 * Android restores on iOS (GRDB) and vice versa. Room's own DDL is replaced by this on creation.
 * PRAGMA user_version = [VERSION].
 */
object GimanoSchema {
    const val VERSION = 1
    const val DB_NAME = "gimano.db"

    /** Tables that make up a backup (outbox is device-local and never backed up). */
    val BACKED_UP_TABLES = listOf("saved_places", "recents", "private_places", "private_place_photos", "planned_drives", "settings")
    val ALL_TABLES = BACKED_UP_TABLES + "outbox"

    val DDL: List<String> = listOf(
        """CREATE TABLE saved_places (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('home','work','favorite')),
  name TEXT NOT NULL,
  icon TEXT NOT NULL DEFAULT 'star',
  lat REAL NOT NULL, lng REAL NOT NULL,
  address TEXT, place_id TEXT,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""",
        "CREATE UNIQUE INDEX ux_saved_home_work ON saved_places(kind) WHERE kind IN ('home','work')",
        """CREATE TABLE recents (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, address TEXT,
  lat REAL NOT NULL, lng REAL NOT NULL, place_id TEXT,
  last_used_at TEXT NOT NULL, use_count INTEGER NOT NULL DEFAULT 1)""",
        """CREATE TABLE private_places (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL,
  lat REAL NOT NULL, lng REAL NOT NULL, address TEXT, description TEXT,
  phone TEXT, website TEXT, opening_hours TEXT, amenities_json TEXT,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""",
        """CREATE TABLE private_place_photos (
  id TEXT PRIMARY KEY, place_id TEXT NOT NULL REFERENCES private_places(id) ON DELETE CASCADE,
  file_name TEXT NOT NULL,
  created_at TEXT NOT NULL)""",
        """CREATE TABLE planned_drives (
  id TEXT PRIMARY KEY, name TEXT, origin_json TEXT, destination_json TEXT NOT NULL,
  waypoints_json TEXT, vehicle TEXT NOT NULL, depart_at TEXT, created_at TEXT NOT NULL)""",
        "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        """CREATE TABLE outbox (id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload_json TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT NOT NULL)""",
    )

    /** Applies the schema to an empty database (used by Room's onCreate and by tests on plain SQLite). */
    fun create(exec: (String) -> Unit) {
        DDL.forEach(exec)
        exec("PRAGMA user_version = $VERSION")
    }
}
