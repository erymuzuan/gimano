package my.gimano.app.core.identity

import java.security.MessageDigest

/**
 * BIP-39 (English wordlist) encoding of the 32-byte identity seed: 256 bits + 8-bit SHA-256 checksum
 * = 24 words of 11 bits. Used only as a human-readable recovery code; the seed is used directly as
 * the Ed25519 seed (no PBKDF2 "mnemonic to seed" step).
 */
class Bip39(private val words: List<String>) {
    init { require(words.size == 2048) { "BIP-39 wordlist must have 2048 words" } }
    private val index = words.withIndex().associate { it.value to it.index }

    fun encode(entropy: ByteArray): List<String> {
        require(entropy.size in setOf(16, 20, 24, 28, 32)) { "entropy must be 128-256 bits" }
        val csBits = entropy.size * 8 / 32
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val totalBits = entropy.size * 8 + csBits
        fun bit(i: Int): Int = if (i < entropy.size * 8) (entropy[i / 8].toInt() shr (7 - i % 8)) and 1
            else (hash[(i - entropy.size * 8) / 8].toInt() shr (7 - (i - entropy.size * 8) % 8)) and 1
        return (0 until totalBits / 11).map { w -> words[(0 until 11).fold(0) { acc, b -> (acc shl 1) or bit(w * 11 + b) }] }
    }

    sealed class Result {
        data class Ok(val entropy: ByteArray) : Result()
        data class UnknownWord(val position: Int, val word: String) : Result()
        object WrongLength : Result()
        object BadChecksum : Result()
    }

    /** Accepts any whitespace, case, and the unique 4-letter prefixes BIP-39 guarantees. */
    fun decode(text: String): Result {
        val ws = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (ws.size !in setOf(12, 15, 18, 21, 24)) return Result.WrongLength
        val idx = ws.mapIndexed { i, w ->
            index[w] ?: words.singleOrNull { w.length >= 4 && it.startsWith(w.take(4)) && it.startsWith(w) }?.let { index[it] }
                ?: return Result.UnknownWord(i + 1, w)
        }
        val totalBits = ws.size * 11
        val csBits = totalBits / 33
        val entBits = totalBits - csBits
        val bits = BooleanArray(totalBits) { i -> (idx[i / 11] shr (10 - i % 11)) and 1 == 1 }
        val entropy = ByteArray(entBits / 8) { b -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or (if (bits[b * 8 + k]) 1 else 0) }.toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        for (k in 0 until csBits) {
            val expected = (hash[k / 8].toInt() shr (7 - k % 8)) and 1 == 1
            if (bits[entBits + k] != expected) return Result.BadChecksum
        }
        return Result.Ok(entropy)
    }
}
