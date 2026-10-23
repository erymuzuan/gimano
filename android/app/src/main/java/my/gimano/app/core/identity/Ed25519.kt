package my.gimano.app.core.identity

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.Sign

/** Ed25519 (libsodium) from a 32-byte seed: the contributor pseudonym's signing key. */
class Ed25519(private val sodium: LazySodium) {
    class KeyPair(val publicKey: ByteArray, internal val secretKey: ByteArray)

    fun newSeed(): ByteArray = sodium.randomBytesBuf(Sign.ED25519_SEEDBYTES)

    fun keyPair(seed: ByteArray): KeyPair {
        require(seed.size == Sign.ED25519_SEEDBYTES) { "seed must be 32 bytes" }
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        check(sodium.cryptoSignSeedKeypair(pk, sk, seed))
        return KeyPair(pk, sk)
    }

    fun sign(kp: KeyPair, message: ByteArray): ByteArray {
        val sig = ByteArray(Sign.ED25519_BYTES)
        check(sodium.cryptoSignDetached(sig, message, message.size.toLong(), kp.secretKey))
        return sig
    }

    fun verify(publicKey: ByteArray, message: ByteArray, sig: ByteArray): Boolean =
        sig.size == Sign.ED25519_BYTES && sodium.cryptoSignVerifyDetached(sig, message, message.size, publicKey)
}
