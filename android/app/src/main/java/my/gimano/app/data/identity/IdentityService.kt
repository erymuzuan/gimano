package my.gimano.app.data.identity

import my.gimano.app.core.identity.Bip39
import my.gimano.app.core.identity.Ed25519
import my.gimano.app.core.identity.RequestSigning

/**
 * The contributor pseudonym (docs/identity-and-reputation.md §1): a random 32-byte seed on this phone,
 * an Ed25519 key derived from it, and the server's userId once registered. Thread-safe; DB work happens
 * on the caller's thread, so call from IO / OkHttp threads.
 */
class IdentityService(
    private val store: IdentityStore,
    private val ed: Ed25519,
    private val bip39: () -> Bip39,
) {
    private class Loaded(val id: StoredIdentity, val keys: Ed25519.KeyPair)
    @Volatile private var cached: Loaded? = null

    private fun load(): Loaded? = cached ?: synchronized(this) {
        cached ?: store.get()?.let { Loaded(it, ed.keyPair(it.seed)) }?.also { cached = it }
    }

    /** The identity, creating a fresh unregistered key on first use (first launch, or a 0.1.0 install upgrading). */
    fun ensure(): StoredIdentity = synchronized(this) {
        load()?.id ?: run {
            store.put("", null, ed.newSeed())
            cached = null
            load()!!.id
        }
    }

    fun current(): StoredIdentity? = load()?.id

    fun publicKeyB64(): String { ensure(); return RequestSigning.b64(load()!!.keys.publicKey) }

    fun sign(message: String): String { ensure(); return RequestSigning.b64(ed.sign(load()!!.keys, message.toByteArray())) }

    /** Signature for a contribution request, or null before an identity exists. */
    fun signRequest(method: String, pathAndQuery: String, ts: Long, body: ByteArray): String? =
        load()?.let { RequestSigning.b64(ed.sign(it.keys, RequestSigning.canonical(method, pathAndQuery, ts, body).toByteArray())) }

    fun markRegistered(userId: String, displayName: String?) = synchronized(this) {
        store.setUser(userId, displayName); cached = null
    }

    /** Adopt a seed from a recovery code (after the server confirmed it) or another source. */
    fun adopt(seed: ByteArray, userId: String, displayName: String?) = synchronized(this) {
        store.put(userId, displayName, seed); cached = null
    }

    /** "Delete my Manotu account": forget this phone's key (the server account is already deleted). */
    fun forget() = synchronized(this) { store.clear(); cached = null }

    /** Call after a backup restore replaced the row. */
    fun reload() = synchronized(this) { store.wrapPlainSeeds(); cached = null }

    fun recoveryWords(): List<String> = bip39().encode(ensure().seed)

    fun publicKeyOf(seed: ByteArray): String = RequestSigning.b64(ed.keyPair(seed).publicKey)
    fun signWith(seed: ByteArray, message: String): String = RequestSigning.b64(ed.sign(ed.keyPair(seed), message.toByteArray()))
    fun decodeRecovery(text: String): Bip39.Result = bip39().decode(text)
}
