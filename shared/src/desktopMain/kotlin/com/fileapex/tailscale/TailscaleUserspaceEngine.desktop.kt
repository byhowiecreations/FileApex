package com.fileapex.tailscale

import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.platform.DesktopPlatformPaths
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun tailscaleUserspaceStateDir(): File =
    File(DesktopPlatformPaths.applicationSupportDirectory(), "tsnet").apply { mkdirs() }

private class DesktopEmbeddedTsnetEngine : TailscaleUserspaceEngine {
    override suspend fun start(authKey: String): TailscaleLinkSnapshot = withContext(Dispatchers.IO) {
        val lib = DesktopTsnetLibrary.loadOrNull()
            ?: return@withContext evaluateTsnetBringUp(
                authKey = authKey,
                nativeLinked = false,
                tailnetIp = ""
            )
        val identity = runCatching { loadLocalIdentity() }.getOrNull()
        val started = DesktopTsnetLibrary.start(
            lib,
            authKey = authKey.trim(),
            dir = tailscaleUserspaceStateDir().absolutePath,
            hostname = tailscaleConsoleHostname(
                identity?.deviceName.orEmpty(),
                identity?.deviceId.orEmpty()
            ),
            sharePort = identity?.sharePort ?: LocalIdentity.DEFAULT_SHARE_PORT
        )
        if (!started.ok) {
            return@withContext tailscaleStartFailure(started.error.ifBlank { "start_failed" })
        }
        snapshotFromBackend(started.backend, started.keyExpired, started.ip)
            .copy(authUrl = DesktopTsnetLibrary.authUrl(lib))
    }

    override fun poll(): TailscaleLinkSnapshot {
        val lib = DesktopTsnetLibrary.loadOrNull()
            ?: return TailscaleLinkSnapshot(TailscalePhase.Down, detail = "tsnet_not_linked")
        return DesktopTsnetLibrary.poll(lib)
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        DesktopTsnetLibrary.stopIfLoaded()
    }

    override suspend fun clearState() = withContext(Dispatchers.IO) {
        val dir = tailscaleUserspaceStateDir()
        val lib = DesktopTsnetLibrary.loadOrNull()
        if (lib != null && DesktopTsnetLibrary.clearState(lib, dir.absolutePath)) {
            return@withContext
        }
        dir.listFiles()?.forEach { child -> child.deleteRecursively() }
    }

    override suspend fun logout(): String? = withContext(Dispatchers.IO) {
        val lib = DesktopTsnetLibrary.loadOrNull() ?: return@withContext "sign_out_failed"
        DesktopTsnetLibrary.logout(lib)
    }

    override fun activeConnections(): Int {
        val lib = DesktopTsnetLibrary.loadOrNull() ?: return 0
        return DesktopTsnetLibrary.activeConnections(lib)
    }

    override fun tailnetPeersJson(): String? {
        val lib = DesktopTsnetLibrary.loadOrNull() ?: return null
        return DesktopTsnetLibrary.peers(lib)
    }
}

internal fun tailscaleLoopbackOrNull(host: String, port: Int): TailscaleLoopbackDial? {
    if (!usesUserspaceDial(host, TailscaleNodeRuntime.state.value.phase)) return null
    val lib = DesktopTsnetLibrary.loadOrNull() ?: return null
    val localPort = DesktopTsnetLibrary.loopback(lib, host.trim(), port)
    val token = DesktopTsnetLibrary.sessionToken(lib)
    if (localPort <= 0 || token.isEmpty()) return null
    return TailscaleLoopbackDial(host = "127.0.0.1", port = localPort, token = token)
}

internal fun tsnetNativeLibraryLoaded(): Boolean = DesktopTsnetLibrary.loaded()

actual fun tailscaleNodeStateFile(): File = File(tailscaleUserspaceStateDir(), "tailscaled.state")

actual fun holdTailscaleProcess() = Unit

actual fun openTailscaleLoginInBrowser(url: String) {
    com.fileapex.platform.PlatformClipboard.openUrlInDefaultBrowser(url)
}

actual fun returnToAppAfterTailscaleLogin() {
    if (com.fileapex.platform.DesktopPlatformPaths.isMacOs()) {
        com.fileapex.platform.DesktopMacTrayCoordinator.showMainWindow()
    }
}

private class TsnetStart(
    val ok: Boolean,
    val ip: String,
    val error: String,
    val backend: String = "",
    val keyExpired: Boolean = false
)

private object DesktopTsnetLibrary {
    private val lock = Any()

    @Volatile
    private var native: FileApexTsnetNative? = null

    fun loaded(): Boolean = native != null

    fun loadOrNull(): FileApexTsnetNative? {
        if (!DesktopPlatformPaths.isMacOs() && !DesktopPlatformPaths.isWindows()) return null
        native?.let { return it }
        synchronized(lock) {
            native?.let { return it }
            val library = resolveLibrary() ?: return null
            val loaded = runCatching {
                Native.load(
                    library.absolutePath,
                    FileApexTsnetNative::class.java,
                    mapOf(Library.OPTION_CALLING_CONVENTION to Function.C_CONVENTION)
                )
            }.getOrElse { error ->
                println("FileApex tsnet: load failed :: ${error.message}")
                null
            } ?: return null
            native = loaded
            println("FileApex tsnet: loaded ${library.absolutePath}")
            return loaded
        }
    }

    fun stopIfLoaded() {
        native?.let { lib ->
            runCatching { lib.fileapex_tsnet_stop() }
        }
    }

    fun start(lib: FileApexTsnetNative, authKey: String, dir: String, hostname: String, sharePort: Int): TsnetStart {
        val ipBuf = Memory(64)
        val errBuf = Memory(512)
        val stateBuf = Memory(64)
        val ok = runCatching {
            lib.fileapex_tsnet_start(authKey, dir, hostname, sharePort, ipBuf, 64, errBuf, 512)
        }.getOrElse { error ->
            return TsnetStart(ok = false, ip = "", error = error.message ?: "start_failed")
        }
        val ip = ipBuf.getString(0, "UTF-8").orEmpty().trim()
        val error = errBuf.getString(0, "UTF-8").orEmpty().trim()
        if (ok == 0) {
            return TsnetStart(ok = false, ip = "", error = error.ifBlank { "start_failed" })
        }
        runCatching { lib.fileapex_tsnet_backend_state(stateBuf, 64) }
        val backend = stateBuf.getString(0, "UTF-8").orEmpty().trim()
        val expired = runCatching { lib.fileapex_tsnet_key_expired() }.getOrDefault(0) != 0
        return TsnetStart(ok = true, ip = ip, error = "", backend = backend, keyExpired = expired)
    }

    fun authUrl(lib: FileApexTsnetNative): String = readNativeString(lib, ::authUrlRead)

    fun poll(lib: FileApexTsnetNative): TailscaleLinkSnapshot {
        val stateBuf = Memory(64)
        val ipBuf = Memory(64)
        runCatching { lib.fileapex_tsnet_backend_state(stateBuf, 64) }
        runCatching { lib.fileapex_tsnet_self_ipv4(ipBuf, 64) }
        val backend = stateBuf.getString(0, "UTF-8").orEmpty().trim()
        val ip = ipBuf.getString(0, "UTF-8").orEmpty().trim()
        val expired = runCatching { lib.fileapex_tsnet_key_expired() }.getOrDefault(0) != 0
        return snapshotFromBackend(backend, expired, ip).copy(authUrl = authUrl(lib))
    }

    private fun authUrlRead(lib: FileApexTsnetNative, buf: Memory) {
        lib.fileapex_tsnet_auth_url(buf, 1024)
    }

    private fun readNativeString(
        lib: FileApexTsnetNative,
        read: (FileApexTsnetNative, Memory) -> Unit
    ): String {
        val buf = Memory(1024)
        val ok = runCatching {
            read(lib, buf)
            true
        }.getOrDefault(false)
        if (!ok) return ""
        return buf.getString(0, "UTF-8").orEmpty().trim()
    }

    fun loopback(lib: FileApexTsnetNative, host: String, port: Int): Int =
        runCatching { lib.fileapex_tsnet_loopback(host, port) }.getOrDefault(0)

    fun sessionToken(lib: FileApexTsnetNative): String {
        val buf = Memory(128)
        val ok = runCatching { lib.fileapex_tsnet_session_token(buf, 128) }.getOrDefault(0)
        if (ok == 0) return ""
        return buf.getString(0, "UTF-8").orEmpty().trim()
    }

    fun clearState(lib: FileApexTsnetNative, dir: String): Boolean =
        runCatching { lib.fileapex_tsnet_clear_state(dir) == 1 }.getOrDefault(false)

    fun logout(lib: FileApexTsnetNative): String? {
        val errBuf = Memory(256)
        val ok = runCatching { lib.fileapex_tsnet_logout(errBuf, 256) }.getOrElse {
            return "sign_out_failed"
        }
        return if (ok == 1) null else "sign_out_failed"
    }

    fun activeConnections(lib: FileApexTsnetNative): Int =
        runCatching { lib.fileapex_tsnet_active_conns() }.getOrDefault(0)

    fun peers(lib: FileApexTsnetNative): String? {
        val buf = Memory(65_536)
        val ok = runCatching { lib.fileapex_tsnet_peers(buf, 65_536) }.getOrDefault(0)
        if (ok == 0) return null
        return buf.getString(0, "UTF-8").orEmpty().ifBlank { "[]" }
    }

    private fun resolveLibrary(): File? =
        if (DesktopPlatformPaths.isWindows()) resolveWindowsDll() else resolveMacDylib()

    private fun resolveWindowsDll(): File? {
        val command = ProcessHandle.current().info().command().orElse(null)
        if (!command.isNullOrBlank()) {
            val besideExe = File(command).canonicalFile.parentFile?.resolve("libFileApexTsnet.dll")
            if (besideExe != null && besideExe.isFile) return besideExe
        }
        val userDir = File(System.getProperty("user.dir") ?: ".")
        listOf(
            File(userDir, "libFileApexTsnet.dll"),
            File(userDir, "windows/build/Tsnet/libFileApexTsnet.dll"),
            userDir.parentFile?.resolve("windows/build/Tsnet/libFileApexTsnet.dll")
        ).forEach { candidate ->
            if (candidate != null && candidate.isFile) return candidate
        }
        return null
    }

    private fun resolveMacDylib(): File? {
        val resources = System.getProperty("compose.application.resources.dir")
        if (!resources.isNullOrBlank()) {
            var cursor: File? = File(resources)
            repeat(6) {
                val current = cursor ?: return@repeat
                if (current.name.endsWith(".app")) {
                    val bundled = File(current, "Contents/Frameworks/libFileApexTsnet.dylib")
                    if (bundled.isFile) return bundled
                }
                cursor = current.parentFile
            }
        }
        val command = ProcessHandle.current().info().command().orElse(null)
        if (!command.isNullOrBlank()) {
            var cursor: File? = File(command).canonicalFile.parentFile
            repeat(10) {
                val current = cursor ?: return@repeat
                if (current.name.endsWith(".app")) {
                    val bundled = File(current, "Contents/Frameworks/libFileApexTsnet.dylib")
                    if (bundled.isFile) return bundled
                }
                cursor = current.parentFile
            }
        }
        val userDir = File(System.getProperty("user.dir") ?: ".")
        listOf(
            File(userDir, "macos/build/Tsnet/libFileApexTsnet.dylib"),
            userDir.parentFile?.resolve("macos/build/Tsnet/libFileApexTsnet.dylib")
        ).forEach { candidate ->
            if (candidate != null && candidate.isFile) return candidate
        }
        return null
    }
}

private interface FileApexTsnetNative : Library {
    fun fileapex_tsnet_start(
        authKey: String,
        dir: String,
        hostname: String,
        sharePort: Int,
        outIP: Pointer,
        outIPLen: Int,
        outErr: Pointer,
        outErrLen: Int
    ): Int

    fun fileapex_tsnet_stop()

    fun fileapex_tsnet_logout(outErr: Pointer, outErrLen: Int): Int

    fun fileapex_tsnet_loopback(host: String, port: Int): Int

    fun fileapex_tsnet_session_token(outToken: Pointer, outTokenLen: Int): Int

    fun fileapex_tsnet_backend_state(outState: Pointer, outStateLen: Int)

    fun fileapex_tsnet_auth_url(outUrl: Pointer, outUrlLen: Int)

    fun fileapex_tsnet_self_ipv4(outIp: Pointer, outIpLen: Int)

    fun fileapex_tsnet_key_expired(): Int

    fun fileapex_tsnet_clear_state(dir: String): Int

    fun fileapex_tsnet_active_conns(): Int

    fun fileapex_tsnet_peers(outPeers: Pointer, outPeersLen: Int): Int
}

actual fun embeddedTsnetEngine(): TailscaleUserspaceEngine = DesktopEmbeddedTsnetEngine()
