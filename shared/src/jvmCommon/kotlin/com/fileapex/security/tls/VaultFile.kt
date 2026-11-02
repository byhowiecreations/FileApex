package com.fileapex.security.tls

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * One secret kept in a file sealed by the first vault that works. The scheme of that vault is the first byte, so a later
 * load uses the same one. [write] never falls back to clear text because a vault is only briefly unavailable, and [read]
 * reports a file that cannot be opened instead of replacing it.
 */
internal class VaultFile(
    private val directory: File,
    private val fileName: String,
    private val vaults: List<TlsSecretVault>,
    private val label: String
) {
    private val file get() = File(directory, fileName)

    fun exists(): Boolean = file.isFile

    fun write(plain: ByteArray) {
        var chosen: TlsSecretVault = PlainTlsVault
        var sealed: ByteArray? = null
        for (vault in vaults + PlainTlsVault) {
            val result = runCatching { vault.seal(plain) }
            if (result.isSuccess) {
                chosen = vault
                sealed = result.getOrThrow()
                break
            }
            val failure = result.exceptionOrNull()
            if (generateSequence(failure) { it.cause }.any { it is com.fileapex.data.settings.SecretUnavailableException }) {
                throw TlsIdentityUnavailableException("secure storage is not available right now", failure)
            }
            println("$label: vault ${vault.scheme} failed to seal - ${failure?.javaClass?.simpleName}")
        }
        writeAtomically(byteArrayOf(chosen.scheme.toByte()) + checkNotNull(sealed))
    }

    fun read(): ByteArray {
        val raw = file.readBytes()
        if (raw.size < 2) throw TlsIdentityUnavailableException("$label file truncated")
        val scheme = raw[0].toInt()
        val vault = (vaults + PlainTlsVault).firstOrNull { it.scheme == scheme }
            ?: throw TlsIdentityUnavailableException("no vault for scheme $scheme on this device")
        return try {
            vault.open(raw.copyOfRange(1, raw.size))
        } catch (e: Exception) {
            throw TlsIdentityUnavailableException("$label could not be opened (scheme $scheme)", e)
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        directory.mkdirs()
        runCatching { Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------")) }
        val temp = File(directory, "$fileName.tmp")
        temp.writeBytes(bytes)
        runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
