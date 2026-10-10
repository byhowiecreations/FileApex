package com.fileapex.security.tls

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
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
    private val file get() = File(directory, FILE_NAME)
    private var cached: TlsIdentity? = null

    @Synchronized
    @Throws(TlsIdentityUnavailableException::class)
    fun getOrCreate(): TlsIdentity {
        cached?.let { return it }
        val loaded = if (file.isFile) load() else null
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
        val identity = TlsIdentityFactory.generate(clock())
        save(identity)
        cached = identity
        println("TlsIdentity: regenerated, pin ${TlsPin.forLog(identity.pin)}")
        return identity
    }

    private fun create(): TlsIdentity {
        val identity = TlsIdentityFactory.generate(clock())
        save(identity)
        println("TlsIdentity: created, pin ${TlsPin.forLog(identity.pin)}")
        return identity
    }

    private fun save(identity: TlsIdentity) {
        val plain = encode(identity)
        var chosen: TlsSecretVault = PlainTlsVault
        var sealed: ByteArray? = null
        for (vault in vaults + PlainTlsVault) {
            val result = runCatching { vault.seal(plain) }
            if (result.isSuccess) {
                chosen = vault
                sealed = result.getOrThrow()
                break
            }
            println("TlsIdentity: vault ${vault.scheme} failed to seal - ${result.exceptionOrNull()?.javaClass?.simpleName}")
        }
        writeAtomically(byteArrayOf(chosen.scheme.toByte()) + checkNotNull(sealed))
    }

    private fun load(): TlsIdentity {
        val raw = file.readBytes()
        if (raw.size < 2) throw TlsIdentityUnavailableException("identity file truncated")
        val scheme = raw[0].toInt()
        val vault = (vaults + PlainTlsVault).firstOrNull { it.scheme == scheme }
            ?: throw TlsIdentityUnavailableException("no vault for scheme $scheme on this device")
        return try {
            decode(vault.open(raw.copyOfRange(1, raw.size)))
        } catch (e: Exception) {
            throw TlsIdentityUnavailableException("identity could not be opened (scheme $scheme)", e)
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        directory.mkdirs()
        runCatching {
            Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
        val temp = File(directory, "$FILE_NAME.tmp")
        temp.writeBytes(bytes)
        runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
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
        const val MAX_PART = 16 * 1024
    }
}
