package my.gimano.app.data.events

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import my.gimano.app.core.events.EventFileWriter
import my.gimano.app.core.events.EventJsonWriter
import my.gimano.app.core.events.EventLinks
import my.gimano.app.core.events.EventPackException
import my.gimano.app.core.events.EventPackReader
import my.gimano.app.core.events.EventPackWriter
import my.gimano.app.core.events.EventParser
import my.gimano.app.core.events.EventPlan
import my.gimano.app.core.events.EventRevisions
import my.gimano.app.core.events.HostDraft
import my.gimano.app.core.events.HostDraftRules
import my.gimano.app.core.events.SealedInvitation
import my.gimano.app.core.events.SealedPack
import my.gimano.app.core.events.ShareMode
import my.gimano.app.core.events.SecretCodeV2
import my.gimano.app.core.events.decodeHostDraft
import java.time.Instant

/** An event this phone organises, as listed and edited. */
data class HostedEvent(
    val draft: HostDraft,
    /** The last published revision (null before the first "Kongsi jemputan"). */
    val published: EventPlan?,
    /** The draft has guest-visible changes not yet published. */
    val changed: Boolean,
    /** The event has a live short link (§5.6) that "Tamatkan pautan" can end. */
    val shortLink: Boolean = false,
)

/**
 * The organizer's short link (§5.6): [secret] (16 bytes, made here), the server's [dropId] and [writeToken], and the
 * revision hash (b64u) of the bytes last uploaded ([uploadedHash]), so an unchanged revision is not uploaded again.
 * Sealed by the events vault in the event's row, next to its keys.
 */
/** A sealed drop blob and the revision hash (b64u) of the event bytes inside it. */
class DropUpload(val blob: ByteArray, val hash: String)

@kotlinx.serialization.Serializable
data class HostDrop(val dropId: String, val secretB64u: String, val writeToken: String, val uploadedHash: String? = null) {
    val ref: my.gimano.app.core.events.DropRef
        get() = my.gimano.app.core.events.DropRef(dropId, my.gimano.app.core.events.B64u.decodeExact(secretB64u, my.gimano.app.core.events.DropLinks.SECRET_BYTES)!!)

    fun encode(): ByteArray = kotlinx.serialization.json.Json.encodeToString(serializer(), this).toByteArray()

    override fun toString() = "HostDrop($dropId, …)"   // never the secret or the token

    companion object {
        fun decode(b: ByteArray): HostDrop = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), b.toString(Charsets.UTF_8))
    }
}

/**
 * One sealed invitation, held in memory only while the share screen is open. [key] is the link's pack key. The default
 * file ([file], suite 3) is the link's own key, nonce and ciphertext framed as a file (§5.2, no code). For "Acara
 * sensitif" a suite-2 file is sealed under the event's [secretCode] (`XXXX-XXXX`, the same for every revision) with a
 * fresh salt ([codeFile]). Never log any of them. Files always carry the full event; [stopsOnlyLink] is the same
 * revision without route lines (§5.5), only when the full link is too long and that one fits.
 */
class Publication(
    val plan: EventPlan, val key: ByteArray, val invitation: SealedInvitation, val reused: Boolean, val previous: EventPlan?,
    /** The event's 8-character file code, `XXXX-XXXX`. */
    val secretCode: String,
    /** The suite-2 file, sealed only for sensitive events (Argon2id); null until then. */
    private val codeFileBytes: ByteArray? = null,
) {
    /** The suite-3 `.manotu-event` file of this revision: the same bytes as the full link (§5.2), no code. */
    fun file(): ByteArray = EventLinks.openFile(key, sealed)
    /** The suite-2 file ("Acara sensitif"), when sealed. */
    fun codeFile(): ByteArray? = codeFileBytes?.copyOf()
    fun withCodeFile(bytes: ByteArray) = Publication(plan, key, invitation, reused, previous, secretCode, bytes)

    val sealed: SealedPack get() = invitation.full
    val mode: ShareMode get() = invitation.mode
    val fragmentLength: Int get() = invitation.fullFragmentLength
    /** Null when the fragment would exceed 16 000 characters: offer the file (or the stops-only link) instead. */
    fun link(site: String): String? = runCatching { EventLinks.link(site, key, sealed) }.getOrNull()
    /** "Pautan tanpa laluan": only in [ShareMode.STOPS_ONLY_OR_FILE]. */
    fun stopsOnlyLink(site: String): String? =
        invitation.stopsOnly?.takeIf { mode == ShareMode.STOPS_ONLY_OR_FILE }?.let { runCatching { EventLinks.link(site, key, it) }.getOrNull() }
}

/**
 * Host side of Events (docs/events.md): drafts, the per-event organizer key, pack key and file secret code, and
 * published revisions, all on this phone only, sealed by [vault] in manotu-events.db (excluded from backups). Never
 * logs event content or keys and never touches the network.
 *
 * Sealed secrets: `seed(32) ‖ linkKey(32) ‖ fileCode(8 ASCII)`. Events created before file suite 2 have only the
 * first 64 bytes; they get a code on their next publish ([publish]), kept from then on.
 */
class HostStore(
    private val dao: HostEventDao,
    private val vault: EventVault,
    private val writer: EventPackWriter,
    private val clock: () -> Instant = Instant::now,
    private val onChanged: suspend () -> Unit = {},
    /** Sealing runs Argon2id (64 MiB): never on the main thread. */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    private val files = EventFileWriter(writer, EventPackReader(writer.sodium))

    companion object {
        private const val SEED_END = 32
        private const val KEY_END = 64
        private const val SECRETS_V2 = KEY_END + SecretCodeV2.CHARS

        /** The file code in sealed secrets, or null for an event from before suite 2. */
        internal fun codeOf(secrets: ByteArray): String? =
            if (secrets.size >= SECRETS_V2) String(secrets, KEY_END, SecretCodeV2.CHARS, Charsets.US_ASCII) else null
    }

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun draftOf(e: HostEventEntity): HostDraft? = runCatching { decodeHostDraft(vault.open(e.draft)) }.getOrNull()

    private fun publishedOf(e: HostEventEntity): ByteArray? = e.published?.let { runCatching { vault.open(it) }.getOrNull() }

    private fun view(e: HostEventEntity): HostedEvent? {
        val d = draftOf(e) ?: return null
        val bytes = publishedOf(e)
        val pub = bytes?.let { runCatching { EventParser.parse(it) }.getOrNull() }
        val changed = pub == null || runCatching {
            !EventJsonWriter.write(HostDraftRules.toPlan(d, pub.rev, pub.parent, pub.publishedAt)).contentEquals(bytes)
        }.getOrDefault(true)
        return HostedEvent(d, pub, changed, shortLink = e.dropRef != null)
    }

    fun events(): Flow<List<HostedEvent>> = dao.allFlow().map { rows ->
        val now = clock().toEpochMilli()
        rows.filter { it.expiresAt == null || it.expiresAt > now }.mapNotNull(::view)
    }

    suspend fun load(id: String): HostedEvent? = dao.get(id)?.takeIf { it.expiresAt == null || it.expiresAt > clock().toEpochMilli() }?.let(::view)

    suspend fun isHosted(id: String): Boolean = dao.get(id) != null

    /** A new event with a fresh organizer seed, pack key and file secret code (72 bytes, sealed). */
    suspend fun create(d: HostDraft) {
        val seed = writer.newSeed()
        val key = writer.newPackKey()
        val code = SecretCodeV2.parse(writer.newSecretCode())
        val secrets = seed + key + code
        code.fill(0)
        try {
            dao.insert(HostEventEntity(d.id, vault.seal(d.encode()), vault.seal(secrets), null, null, null, clock().toEpochMilli()))
        } finally {
            seed.fill(0); key.fill(0); secrets.fill(0)
        }
        changed()
    }

    suspend fun save(d: HostDraft) {
        val e = dao.get(d.id) ?: return create(d)
        dao.update(e.copy(draft = vault.seal(d.encode()), updatedAt = clock().toEpochMilli()))
        changed()
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        changed()
    }

    /** The organizer safety code guests see behind "Penganjur disahkan" (§7.3), for the host's Semak screen. */
    suspend fun safetyCode(id: String): String? {
        val e = dao.get(id) ?: return null
        val s = runCatching { vault.open(e.secrets) }.getOrNull() ?: return null
        return try { my.gimano.app.core.events.EventHashes.safetyCode(writer.organizerPub(s.copyOfRange(0, SEED_END))) } finally { s.fill(0) }
    }

    /** Opening one's own invitation link: the organizer key in the pack is this phone's key for that event. */
    suspend fun isOwn(eventId: String, organizerPub: ByteArray): Boolean {
        val e = dao.get(eventId) ?: return false
        val s = runCatching { vault.open(e.secrets) }.getOrNull() ?: return false
        return try { writer.organizerPub(s.copyOfRange(0, 32)).contentEquals(organizerPub) } finally { s.fill(0) }
    }

    /**
     * Seals the next revision (or the unchanged last one again, with a fresh nonce) and records it. Throws
     * [EventPackException] when the event does not validate.
     */
    suspend fun publish(id: String, codeFile: Boolean? = null): Publication {
        var e = dao.get(id) ?: throw EventPackException(EventPackException.INVALID_EVENT, "unknown event")
        val d = draftOf(e) ?: throw EventPackException(EventPackException.INVALID_EVENT, "draft unreadable")
        // Link-first sharing: no file is sealed unless asked for (the open file is framed on demand, Publication.file()).
        val withCodeFile = codeFile ?: false
        val prepared = EventRevisions.prepare(d, publishedOf(e), clock())
        var s = vault.open(e.secrets)
        try {
            if (codeOf(s) == null) {
                // Hosted before file suite 2: give the event its code now and keep it for every later revision.
                val newCode = SecretCodeV2.parse(writer.newSecretCode())
                val upgraded = s.copyOfRange(0, KEY_END) + newCode
                newCode.fill(0); s.fill(0)
                s = upgraded
                e = e.copy(secrets = vault.seal(s))
                dao.update(e)
            }
            val code = SecretCodeV2.format(codeOf(s)!!)
            val seed = s.copyOfRange(0, SEED_END)
            val key = s.copyOfRange(SEED_END, KEY_END)
            // Same revision, same organiser key and pack key for the stops-only variant; the stored bytes stay the full ones.
            // The default file (suite 3) is framed from the full link's sealed pack (no Argon2). Sensitive events also
            // get a suite-2 file sealed under the event's code with a fresh salt and nonce.
            val (sealed, file) = try {
                withContext(work) {
                    SealedInvitation.seal(writer, prepared.bytes, seed, key) to (if (withCodeFile) files.seal(prepared.bytes, seed, code) else null)
                }
            } finally { seed.fill(0) }
            if (!prepared.reused) {
                dao.update(e.copy(published = vault.seal(prepared.bytes), publishedRev = prepared.plan.rev,
                    expiresAt = prepared.plan.expiresAt.toEpochMilli(), updatedAt = clock().toEpochMilli()))
                changed()
            }
            return Publication(prepared.plan, key, sealed, prepared.reused, prepared.previous, code, file)
        } finally {
            s.fill(0)
        }
    }

    /**
     * "Acara sensitif" switched on at the share screen: the suite-2 file of the last published revision (exactly those
     * bytes) under the event's code, fresh salt and nonce. One Argon2id run, on [work].
     */
    suspend fun sealCodeFile(id: String): ByteArray {
        val e = dao.get(id) ?: throw EventPackException(EventPackException.INVALID_EVENT, "unknown event")
        val bytes = publishedOf(e) ?: throw EventPackException(EventPackException.INVALID_EVENT, "not published")
        val s = vault.open(e.secrets)
        try {
            val code = SecretCodeV2.format(codeOf(s) ?: throw EventPackException(EventPackException.INVALID_EVENT, "no code"))
            val seed = s.copyOfRange(0, SEED_END)
            try { return withContext(work) { files.seal(bytes, seed, code) } } finally { seed.fill(0) }
        } finally {
            s.fill(0)
        }
    }

    // --- Short link (§5.6) ------------------------------------------------------------------------------------

    /** The event's short link, if it has one (sealed with the keys; never in a backup). */
    suspend fun drop(id: String): HostDrop? {
        val sealed = dao.get(id)?.dropRef ?: return null
        return runCatching { HostDrop.decode(vault.open(sealed)) }.getOrNull()
    }

    /** Records (or with null forgets) the event's short link. */
    suspend fun saveDrop(id: String, drop: HostDrop?) {
        val e = dao.get(id) ?: return
        dao.update(e.copy(dropRef = drop?.let { vault.seal(it.encode()) }))
        changed()
    }

    /** A new 16-byte drop secret, made on this phone; it stays the same for every revision of the event. */
    fun newDropSecret(): ByteArray = writer.sodium.randomBytesBuf(my.gimano.app.core.events.DropLinks.SECRET_BYTES)

    /**
     * The blob to upload: the last published revision (exactly those bytes, always the full event) sealed under
     * HKDF([secret]) with a fresh nonce, opened again before it leaves the phone. Null before the first publication.
     */
    suspend fun dropBlob(id: String, secret: ByteArray): DropUpload? {
        val e = dao.get(id) ?: return null
        val bytes = publishedOf(e) ?: return null
        val s = vault.open(e.secrets)
        try {
            val seed = s.copyOfRange(0, SEED_END)
            try {
                val blob = withContext(work) { my.gimano.app.core.events.EventDrops.seal(writer, bytes, seed, secret) }
                return DropUpload(blob, my.gimano.app.core.events.B64u.encode(my.gimano.app.core.events.EventHashes.revisionHash(bytes)))
            } finally { seed.fill(0) }
        } finally {
            s.fill(0)
        }
    }

    /** b64u revision hash of the last published bytes (what an up-to-date short link holds), or null. */
    suspend fun publishedHash(id: String): String? =
        dao.get(id)?.let(::publishedOf)?.let { my.gimano.app.core.events.B64u.encode(my.gimano.app.core.events.EventHashes.revisionHash(it)) }

    /** §7.5 for the host too: keys, draft and revisions go at the published `expiresAt`. */
    suspend fun purgeExpired(): Int {
        val n = dao.deleteExpired(clock().toEpochMilli())
        if (n > 0) changed()
        return n
    }

    suspend fun nextExpiry(): Instant? = dao.nextExpiry()?.let(Instant::ofEpochMilli)

    private suspend fun changed() {
        _version.value = _version.value + 1
        runCatching { onChanged() }
    }
}
