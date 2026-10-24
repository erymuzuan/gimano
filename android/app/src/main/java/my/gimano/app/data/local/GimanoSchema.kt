package my.gimano.app.data.local

/**
 * The shared on-device schema, copied VERBATIM from docs/privacy-and-backup.md so a backup made on
 * Android restores on iOS (GRDB) and vice versa. Room's own DDL is replaced by this on creation.
 * PRAGMA user_version = [VERSION].
 */
object GimanoSchema {
    const val VERSION = 3
    const val DB_NAME = "gimano.db"

    /** Tables that make up a backup (outbox is device-local and never backed up). */
    val BACKED_UP_TABLES = listOf(
        "saved_places", "recents", "private_places", "private_place_photos", "planned_drives", "personal_routes", "settings", "identity",
    )
    /**
     * Tables every backup has once [migrateAttached] has brought it to [VERSION] (schema 1 backups have no `identity`;
     * it is adopted separately and only when the backup carries one).
     */
    val REQUIRED_TABLES = BACKED_UP_TABLES - "identity"
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
        PERSONAL_ROUTES_DDL,
        "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        IDENTITY_DDL,
        """CREATE TABLE outbox (id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload_json TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT NOT NULL)""",
    )

    /** Schema 2: the contributor pseudonym's signing key (single row). */
    const val IDENTITY_DDL = """CREATE TABLE identity (
  user_id TEXT PRIMARY KEY, display_name TEXT,
  seed_b64 TEXT NOT NULL,
  created_at TEXT NOT NULL)"""

    /** Schema 3: "Laluan saya", routes from the route builder (`route_json` = [my.gimano.app.core.routes.PersonalRouteJson]). */
    const val PERSONAL_ROUTES_DDL = """CREATE TABLE personal_routes (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  mode TEXT NOT NULL CHECK (mode IN ('car','motorcycle','bicycle','walk')),
  route_json TEXT NOT NULL,
  distance_m INTEGER, duration_s INTEGER,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL)"""

    /** Tables added after schema 1, in order (the schema 1 → [VERSION] migrations, all idempotent). */
    private val ADDED_TABLES = listOf("identity" to IDENTITY_DDL, "personal_routes" to PERSONAL_ROUTES_DDL)

    /** The migration statements, for the DB [schema] (`main`, or an attached backup copy such as `bk`). */
    fun migrations(schema: String = "main"): List<String> = ADDED_TABLES.map { (t, ddl) ->
        ddl.replaceFirst("CREATE TABLE $t", "CREATE TABLE IF NOT EXISTS $schema.$t")
    }

    /**
     * Brings an existing DB up to the shared schema, idempotently (runs on every open). Tables added after
     * schema 1 are not Room entities, so Room never sees them (and Room's own version, in user_version, stays 1).
     */
    fun upgrade(exec: (String) -> Unit) {
        migrations().forEach(exec)
    }

    /** Migrates an attached older backup copy ([schema]) to [VERSION] before its rows are copied over. */
    fun migrateAttached(schema: String, exec: (String) -> Unit) {
        migrations(schema).forEach(exec)
    }

    /** Applies the schema to an empty database (used by Room's onCreate and by tests on plain SQLite). */
    fun create(exec: (String) -> Unit) {
        DDL.forEach(exec)
        exec("PRAGMA user_version = $VERSION")
    }
}
