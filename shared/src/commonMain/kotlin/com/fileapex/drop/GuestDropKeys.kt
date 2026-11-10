package com.fileapex.drop

/**
 * The guest's endpoint key is derived from the link secret, so the sender can list the guest's endpoint ids with the drop relay
 * before anyone opens the link. The browser derives the same keys (`native/dropweb/src/guestkey.rs`); both are tested against
 * the same vectors, computed with an independent HKDF and RFC 8032 arithmetic.
 */
object GuestDropKeys {
    /** How many guest keys one link has; the page picks one at random so two visitors do not share an id. */
    const val COUNT = 2

    private val SALT = "fileapex-drop-guest-v1".encodeToByteArray()

    /** HKDF-SHA256 of the secret (RFC 5869, one 32-byte block): the Ed25519 seed of guest key [index]. */
    fun seed(secret: String, index: Int): ByteArray {
        require(index >= 0) { "negative guest key index" }
        val prk = hmac(SALT, secret.encodeToByteArray())
        return hmac(prk, "guest-key-$index".encodeToByteArray() + byteArrayOf(1))
    }

    /** The 64 lowercase hex characters an endpoint with this Ed25519 seed connects as. */
    fun endpointId(seed: ByteArray): String = ed25519PublicKey(seed).joinToString("") { "%02x".format(it) }

    fun guestIds(secret: String): List<String> = (0 until COUNT).map { endpointId(seed(secret, it)) }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

expect fun ed25519PublicKey(seed: ByteArray): ByteArray
