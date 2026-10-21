package com.fileapex.security

import com.fileapex.data.settings.SecretSealer
import com.fileapex.data.settings.SecretUnavailableException
import com.fileapex.security.tls.GcmBlob
import com.fileapex.security.tls.TlsSecretVault
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Lets any platform vault seal settings secrets. */
class VaultSealer(private val vault: TlsSecretVault) : SecretSealer {
    override fun seal(plain: ByteArray): ByteArray = vault.seal(plain)
    override fun open(sealed: ByteArray): ByteArray = vault.open(sealed)
    override fun needsReseal(sealed: ByteArray): Boolean = vault.needsReseal(sealed)
}

/** Same stored format as [GcmBlob]. */
internal object SecretAes {
    fun seal(key: ByteArray, plain: ByteArray): ByteArray = GcmBlob.sealWithRandomIv(SecretKeySpec(key, "AES"), plain)

    fun open(key: ByteArray, sealed: ByteArray): ByteArray = GcmBlob.open(SecretKeySpec(key, "AES"), sealed)
}

/**
 * Wrapping key kept in an owner-only file next to the data. This keeps secrets out of backups, copies of the settings
 * file and casual reading, but not from another process of the same user: it is the fallback where the platform has no
 * secret store.
 */
class FileKeyVault(private val directory: File) : TlsSecretVault {
    override val scheme = SCHEME
    @Volatile private var cached: ByteArray? = null

    override fun seal(plain: ByteArray): ByteArray = SecretAes.seal(key(), plain)
    override fun open(sealed: ByteArray): ByteArray = SecretAes.open(key(), sealed)

    @Synchronized
    private fun key(): ByteArray {
        cached?.let { return it }
        val file = File(directory, KEY_FILE)
        val existing = if (file.isFile) file.readBytes() else null
        if (existing != null && existing.size == KEY_BYTES) return existing.also { cached = it }
        check(existing == null) { "wrapping key file has an unexpected size" }
        directory.mkdirs()
        runCatching { Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------")) }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val temp = File(directory, "$KEY_FILE.tmp")
        temp.writeBytes(fresh)
        runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cached = fresh
        return fresh
    }

    companion object {
        const val SCHEME = 5
        private const val KEY_FILE = "settings-wrap.key"
        private const val KEY_BYTES = 32
    }
}

/** Runs a command with optional stdin; returns the exit code and stdout. Replaceable in tests. */
fun interface CommandRunner {
    fun run(args: List<String>, stdin: String?): Pair<Int, String>
}

internal object ProcessCommandRunner : CommandRunner {
    override fun run(args: List<String>, stdin: String?): Pair<Int, String> {
        val process = ProcessBuilder(args).redirectErrorStream(false).start()
        if (stdin != null) process.outputStream.use { it.write(stdin.toByteArray()) } else process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "${args.first()} timed out" }
        return process.exitValue() to out
    }
}

/**
 * Linux: the wrapping key lives in the desktop keyring (`secret-tool`, libsecret) when there is one, passed on stdin so it
 * never shows in a process list. Without a keyring it falls back to [FileKeyVault]. Each sealed value records which key
 * wrapped it, so a value sealed with the keyring is never opened with the file key or the other way round.
 */
class LinuxSecretVault(
    private val fallback: FileKeyVault,
    private val runner: CommandRunner = ProcessCommandRunner
) : TlsSecretVault {
    override val scheme = SCHEME
    @Volatile private var keyringKey: ByteArray? = null

    /** New secrets always use the key file: it survives a keyring reset, which a keyring key does not. */
    override fun seal(plain: ByteArray): ByteArray = byteArrayOf(KIND_FILE) + fallback.seal(plain)

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.isNotEmpty()) { "sealed blob empty" }
        val body = sealed.copyOfRange(1, sealed.size)
        return when (sealed[0]) {
            KIND_KEYRING -> SecretAes.open(keyringKeyOrThrow(), body)
            KIND_FILE -> fallback.open(body)
            else -> error("unknown sealed kind")
        }
    }

    override fun needsReseal(sealed: ByteArray): Boolean = sealed.firstOrNull() == KIND_KEYRING

    /** Reads the keyring key that an older version sealed with. It is never created here: a new key would orphan every value sealed with the old one. */
    @Synchronized
    private fun keyringKeyOrThrow(): ByteArray {
        keyringKey?.let { return it }
        val found = runCatching { lookup() }.getOrNull()
            ?: throw SecretUnavailableException("the desktop keyring key cannot be read")
        return found.also { keyringKey = it }
    }

    private fun lookup(): ByteArray? {
        val (code, out) = runner.run(listOf("secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT), null)
        val hex = out.trim()
        if (code != 0 || hex.length != 64) return null
        return ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        const val SCHEME = 4
        private const val SERVICE = "com.fileapex.settings"
        private const val ACCOUNT = "wrap-key"
        private const val KIND_KEYRING: Byte = 1
        private const val KIND_FILE: Byte = 2
    }
}
