package com.fileapex.security.tls

import com.fileapex.platform.DesktopPlatformPaths
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/**
 * Random AES key kept in the login Keychain through the `security` tool, which is also the only reader,
 * so no access prompt appears. The key is briefly visible in this user's process list while it is added.
 */
class MacKeychainTlsVault(private val service: String = SERVICE) : TlsSecretVault {
    override val scheme = SCHEME

    override fun seal(plain: ByteArray) = GcmBlob.sealWithRandomIv(key(), plain)
    override fun open(sealed: ByteArray) = GcmBlob.open(key(), sealed)

    private fun key(): SecretKeySpec {
        readHex()?.let { return SecretKeySpec(it, "AES") }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = fresh.joinToString("") { "%02x".format(it) }
        val (code, _) = run("add-generic-password", "-U", "-s", service, "-a", ACCOUNT, "-w", hex)
        check(code == 0) { "keychain add failed ($code)" }
        return SecretKeySpec(fresh, "AES")
    }

    private fun readHex(): ByteArray? {
        val (code, out) = run("find-generic-password", "-s", service, "-a", ACCOUNT, "-w")
        if (code != 0) return null
        val hex = out.trim()
        if (hex.length != 64) return null
        return ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("/usr/bin/security") + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "security timed out" }
        return process.exitValue() to out
    }

    companion object {
        private const val SCHEME = 2
        const val SERVICE = "com.fileapex.tls"
        const val ACCOUNT = "wrap-key"
    }
}

/** Windows DPAPI, bound to the signed-in user. */
class WindowsDpapiTlsVault : TlsSecretVault {
    override val scheme = SCHEME

    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null
    }

    private interface Crypt32 : Library {
        fun CryptProtectData(
            dataIn: DataBlob, description: WString?, entropy: DataBlob?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, dataOut: DataBlob
        ): Boolean

        fun CryptUnprotectData(
            dataIn: DataBlob, description: Pointer?, entropy: DataBlob?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, dataOut: DataBlob
        ): Boolean
    }

    private interface Kernel32 : Library {
        fun LocalFree(handle: Pointer?): Pointer?
    }

    override fun seal(plain: ByteArray) = transform(plain, protect = true)
    override fun open(sealed: ByteArray) = transform(sealed, protect = false)

    private fun transform(input: ByteArray, protect: Boolean): ByteArray {
        val crypt = Native.load("Crypt32", Crypt32::class.java)
        val kernel = Native.load("Kernel32", Kernel32::class.java)
        val memory = Memory(input.size.toLong()).also { it.write(0, input, 0, input.size) }
        val inBlob = DataBlob().also { it.cbData = input.size; it.pbData = memory }
        val outBlob = DataBlob()
        val ok = if (protect) {
            crypt.CryptProtectData(inBlob, null, null, null, null, UI_FORBIDDEN, outBlob)
        } else {
            crypt.CryptUnprotectData(inBlob, null, null, null, null, UI_FORBIDDEN, outBlob)
        }
        check(ok) { "dpapi call failed" }
        try {
            return checkNotNull(outBlob.pbData).getByteArray(0, outBlob.cbData)
        } finally {
            kernel.LocalFree(outBlob.pbData)
        }
    }

    private companion object {
        const val SCHEME = 3
        const val UI_FORBIDDEN = 0x1
    }
}

object DesktopTlsIdentity {
    /** One store per process: the server front, the JVM client and the native Mac client must share one identity. */
    fun store(): TlsIdentityStore = shared

    private val shared: TlsIdentityStore by lazy { create() }

    private fun create(): TlsIdentityStore {
        val vaults = when (DesktopPlatformPaths.desktopOs) {
            DesktopPlatformPaths.DesktopOs.MacOs -> listOf(MacKeychainTlsVault())
            DesktopPlatformPaths.DesktopOs.Windows -> listOf(WindowsDpapiTlsVault())
            DesktopPlatformPaths.DesktopOs.Other -> emptyList()
        }
        return TlsIdentityStore(File(DesktopPlatformPaths.applicationSupportDirectory(), "tls"), vaults)
    }
}
