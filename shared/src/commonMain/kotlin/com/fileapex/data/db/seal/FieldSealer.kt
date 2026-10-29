package com.fileapex.data.db.seal

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals one text column value as `fe1:` + base64(nonce + AES-256-GCM ciphertext and tag). The associated data is
 * `table.column`, so a sealed value is useless in another column. Empty text stays empty. A value without the prefix is
 * legacy plain text and is returned as is.
 */
class FieldSealer(key: ByteArray) {
    init { require(key.size == KEY_BYTES) { "field key must be 32 bytes" } }

    private val secretKey = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    fun seal(table: String, column: String, plain: String): String {
        if (plain.isEmpty()) return plain
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(table, column))
        return PREFIX + Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain.encodeToByteArray()))
    }

    /** The plain text, or null when a sealed value cannot be opened (wrong key, altered, moved to another column). */
    fun open(table: String, column: String, stored: String): String? {
        if (!isSealed(stored)) return stored
        return try {
            val raw = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            if (raw.size <= NONCE_BYTES + TAG_BITS / 8) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, NONCE_BYTES)))
            cipher.updateAAD(aad(table, column))
            cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES).decodeToString()
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun aad(table: String, column: String) = "$table.$column".encodeToByteArray()

    companion object {
        const val PREFIX = "fe1:"
        const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}
