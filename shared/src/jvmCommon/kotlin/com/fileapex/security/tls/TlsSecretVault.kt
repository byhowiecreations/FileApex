package com.fileapex.security.tls

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps the serialized identity with a platform secret store. Throws if the platform store fails. */
interface TlsSecretVault {
    /** Persisted in the file header; never reuse a value. */
    val scheme: Int
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** No platform protection; the file is owner-only. Fallback and Linux default. */
object PlainTlsVault : TlsSecretVault {
    const val SCHEME = 0
    override val scheme = SCHEME
    override fun seal(plain: ByteArray) = plain
    override fun open(sealed: ByteArray) = sealed
}

/** AES-GCM with 12-byte IV prepended; shared by vaults that hold a wrapping key. */
internal object GcmBlob {
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected GCM IV length" }
        return iv + cipher.doFinal(plain)
    }

    fun sealWithRandomIv(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    fun open(key: SecretKey, sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "sealed blob too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }
}
