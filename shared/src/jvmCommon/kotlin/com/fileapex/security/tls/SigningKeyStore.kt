package com.fileapex.security.tls

import com.fileapex.membership.EcEventSigner
import com.fileapex.membership.EventSigner
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The key this device signs membership records with. It outlives the TLS key: a TLS renewal announces the new pin in a
 * record signed with this key. It is sealed like the TLS identity, a key that cannot be read is never replaced silently,
 * and a device that rejoins after a removal gets a new one ([replaceForRejoin]).
 */
class SigningKeyStore(directory: File, vaults: List<TlsSecretVault>) {
    private val vaultFile = VaultFile(directory, FILE_NAME, vaults, "SigningKey")
    private var cached: KeyPair? = null

    @Synchronized
    @Throws(TlsIdentityUnavailableException::class)
    fun getOrCreate(): KeyPair {
        cached?.let { return it }
        val pair = if (vaultFile.exists()) decode(vaultFile.read()) else generate().also { vaultFile.write(encode(it)) }
        cached = pair
        return pair
    }

    @Synchronized
    @Throws(TlsIdentityUnavailableException::class)
    fun replaceForRejoin(): KeyPair {
        val pair = generate()
        vaultFile.write(encode(pair))
        cached = pair
        return pair
    }

    fun signer(deviceId: String): EventSigner = getOrCreate().let { EcEventSigner(deviceId, it.private, it.public) }

    private fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun encode(pair: KeyPair): ByteArray {
        val private = pair.private.encoded
        val public = pair.public.encoded
        return java.nio.ByteBuffer.allocate(8 + private.size + public.size)
            .putInt(private.size).put(private).putInt(public.size).put(public).array()
    }

    private fun decode(plain: ByteArray): KeyPair = try {
        val buffer = java.nio.ByteBuffer.wrap(plain)
        val private = ByteArray(buffer.getInt().also { require(it in 1..MAX_PART) }).also(buffer::get)
        val public = ByteArray(buffer.getInt().also { require(it in 1..MAX_PART) }).also(buffer::get)
        val factory = KeyFactory.getInstance("EC")
        KeyPair(factory.generatePublic(X509EncodedKeySpec(public)) as PublicKey, factory.generatePrivate(PKCS8EncodedKeySpec(private)))
    } catch (e: java.security.GeneralSecurityException) {
        throw TlsIdentityUnavailableException("signing key is damaged", e)
    } catch (e: IllegalArgumentException) {
        throw TlsIdentityUnavailableException("signing key is damaged", e)
    } catch (e: java.nio.BufferUnderflowException) {
        throw TlsIdentityUnavailableException("signing key is damaged", e)
    }

    private companion object {
        const val FILE_NAME = "signing_key.v1"
        const val MAX_PART = 16 * 1024
    }
}
