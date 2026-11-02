package com.fileapex.security.tls

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

class TlsIdentityUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Persists the device TLS identity. [vaults] are tried in order when saving; the scheme of the vault
 * that succeeded is recorded so a later load uses the same one. A stored identity that cannot be read
 * is never replaced silently: the pin would change without the user knowing. Use [regenerate].
 */
class TlsIdentityStore(
    private val directory: File,
    private val vaults: List<TlsSecretVault>,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val vaultFile = VaultFile(directory, FILE_NAME, vaults, "TlsIdentity")

    /** The key this device signs membership records with, kept in the same directory and sealed by the same vaults. */
    val signingKeys: SigningKeyStore by lazy { SigningKeyStore(directory, vaults) }

    /** The proof written by the last [regenerate] while it is younger than [TlsRotation.MAX_AGE_MS] and names the current key. */
    @Synchronized
    fun currentRotationProof(identity: TlsIdentity): TlsRotationProof? {
        val proof = runCatching { TlsRotation.decode(File(directory, ROTATION_FILE).readText()) }.getOrNull() ?: return null
        val fresh = clock() - proof.issuedAtEpochMs <= TlsRotation.MAX_AGE_MS
        return proof.takeIf { fresh && it.newPin.equals(identity.pin, ignoreCase = true) }
    }
    private var cached: TlsIdentity? = null

    @Synchronized
    @Throws(TlsIdentityUnavailableException::class)
    fun getOrCreate(): TlsIdentity {
        cached?.let { return it }
        val loaded = if (vaultFile.exists()) load() else null
        val identity = when {
            loaded == null -> create()
            TlsIdentityFactory.needsReissue(loaded, clock()) -> TlsIdentityFactory.reissue(loaded, clock()).also(::save)
            else -> loaded
        }
        cached = identity
        return identity
    }

    /** Explicit recovery for a lost key. The pin changes; peers must re-confirm. */
    @Synchronized
    fun regenerate(): TlsIdentity {
        val previous = cached ?: runCatching { if (vaultFile.exists()) load() else null }.getOrNull()
        val identity = TlsIdentityFactory.generate(clock())
        save(identity)
        // Peers that pinned the old key accept the new one when it is signed by the old key; kept for 90 days.
        previous?.let { old ->
            val proof = TlsRotation.sign(old.privateKey, old.certificate.publicKey, identity.pin, clock())
            runCatching { File(directory, ROTATION_FILE).writeText(TlsRotation.encode(proof)) }
                .onFailure { println("TlsIdentity: rotation proof not saved - ${it::class.simpleName}") }
        }
        cached = identity
        println("TlsIdentity: regenerated, pin ${TlsPin.forLog(identity.pin)}")
        return identity
    }

    /**
     * A device the cluster removed comes back as a new join with a new key, so no pin the old cluster holds for it can
     * match. The new key is built and checked in memory first, written atomically, and only then replaces the one in use;
     * if it cannot be saved the old key stays. No rotation proof is written: nobody should accept it on the old key's word.
     */
    @Synchronized
    @Throws(TlsIdentityUnavailableException::class)
    fun replaceForRejoin(): TlsIdentity {
        val identity = TlsIdentityFactory.generate(clock())
        check(isUsable(identity)) { "generated TLS identity failed its self-check" }
        save(identity)
        runCatching { File(directory, ROTATION_FILE).delete() }
        cached = identity
        println("TlsIdentity: replaced after removal, pin ${TlsPin.forLog(identity.pin)}")
        return identity
    }

    private fun isUsable(identity: TlsIdentity): Boolean = runCatching {
        val spki = java.security.MessageDigest.getInstance("SHA-256").digest(identity.certificate.publicKey.encoded)
        if (!spki.joinToString("") { "%02x".format(it) }.equals(identity.pin, ignoreCase = true)) return false
        val probe = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val signature = java.security.Signature.getInstance("SHA256withECDSA").run { initSign(identity.privateKey); update(probe); sign() }
        java.security.Signature.getInstance("SHA256withECDSA").run { initVerify(identity.certificate.publicKey); update(probe); verify(signature) }
    }.getOrDefault(false)

    private fun create(): TlsIdentity {
        val identity = TlsIdentityFactory.generate(clock())
        save(identity)
        println("TlsIdentity: created, pin ${TlsPin.forLog(identity.pin)}")
        return identity
    }

    private fun save(identity: TlsIdentity) = vaultFile.write(encode(identity))

    private fun load(): TlsIdentity = try {
        decode(vaultFile.read())
    } catch (e: java.security.GeneralSecurityException) {
        throw TlsIdentityUnavailableException("identity could not be decoded", e)
    } catch (e: IllegalArgumentException) {
        throw TlsIdentityUnavailableException("identity could not be decoded", e)
    } catch (e: java.io.IOException) {
        throw TlsIdentityUnavailableException("identity could not be decoded", e)
    }

    private fun encode(identity: TlsIdentity): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            val key = identity.privateKey.encoded
            val cert = identity.certificate.encoded
            it.writeInt(key.size); it.write(key)
            it.writeInt(cert.size); it.write(cert)
        }
        return out.toByteArray()
    }

    private fun decode(plain: ByteArray): TlsIdentity {
        DataInputStream(ByteArrayInputStream(plain)).use {
            val key = ByteArray(it.readInt().also { n -> require(n in 1..MAX_PART) }).also(it::readFully)
            val cert = ByteArray(it.readInt().also { n -> require(n in 1..MAX_PART) }).also(it::readFully)
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(key))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
            return TlsIdentity(privateKey, certificate)
        }
    }

    private companion object {
        const val FILE_NAME = "tls_identity.v1"
        const val ROTATION_FILE = "tls_rotation.v1"
        const val MAX_PART = 16 * 1024
    }
}
