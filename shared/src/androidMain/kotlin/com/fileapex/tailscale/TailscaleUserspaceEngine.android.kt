package com.fileapex.tailscale

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import java.lang.ref.WeakReference
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.di.FileApexServices
import com.fileapex.platform.ShareServerRestartCoordinator
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Userspace tsnet state directory. This path is the node home for an in-process stack.
 * System VPN registration is intentionally absent.
 */
internal fun tailscaleUserspaceStateDir(): File {
    val base = androidAppContextOrNull()?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
    return File(base, "tsnet").apply { mkdirs() }
}

private class AndroidEmbeddedTsnetEngine : TailscaleUserspaceEngine {
    override suspend fun start(authKey: String): TailscaleLinkSnapshot = withContext(Dispatchers.IO) {
        try {
            val bridge = AndroidTsnetBridge.loadOrNull()
                ?: return@withContext evaluateTsnetBringUp(
                    authKey = authKey,
                    nativeLinked = false,
                    tailnetIp = ""
                )
            val identity = runCatching { loadLocalIdentity() }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }
            AndroidTsnetBridge.publishLinkAddress()
            AndroidLinkWatch.register()
            val started = bridge.start(
                authKey = authKey.trim(),
                dir = tailscaleUserspaceStateDir().absolutePath,
                hostname = tailscaleConsoleHostname(
                    identity?.deviceName.orEmpty(),
                    identity?.deviceId.orEmpty()
                ),
                sharePort = identity?.sharePort ?: LocalIdentity.DEFAULT_SHARE_PORT
            )
            if (!started.ok) {
                val detail = started.error.ifBlank { "start_failed" }.take(180)
                android.util.Log.e("FileApexTsnet", detail)
                return@withContext tailscaleStartFailure(detail)
            }
            snapshotFromBackend(started.backend, started.keyExpired, started.ip)
                .copy(authUrl = bridge.authUrl())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val detail = error.message?.take(180) ?: "start_failed"
            android.util.Log.e("FileApexTsnet", detail)
            tailscaleStartFailure(detail)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        AndroidLinkWatch.unregister()
        AndroidTsnetBridge.stopIfLoaded()
    }

    override suspend fun clearState() = withContext(Dispatchers.IO) {
        val dir = tailscaleUserspaceStateDir()
        val bridge = AndroidTsnetBridge.loadOrNull()
        if (bridge != null && bridge.clearState(dir.absolutePath)) {
            return@withContext
        }
        dir.listFiles()?.forEach { child -> child.deleteRecursively() }
    }

    override suspend fun logout(): String? = withContext(Dispatchers.IO) {
        val bridge = AndroidTsnetBridge.loadOrNull() ?: return@withContext "sign_out_failed"
        bridge.logout()
    }

    override fun poll(): TailscaleLinkSnapshot {
        val bridge = AndroidTsnetBridge.loadOrNull()
            ?: return TailscaleLinkSnapshot(TailscalePhase.Down, detail = "tsnet_not_linked")
        val backend = bridge.backendState()
        val expired = bridge.keyExpired()
        return snapshotFromBackend(backend, expired, bridge.selfIpv4()).copy(authUrl = bridge.authUrl())
    }

    override fun activeConnections(): Int {
        val bridge = AndroidTsnetBridge.loadOrNull() ?: return 0
        return bridge.activeConnections()
    }

    override fun tailnetPeersJson(): String? {
        val bridge = AndroidTsnetBridge.loadOrNull() ?: return null
        return bridge.peers()
    }
}

internal fun tailscaleLoopbackOrNull(host: String, port: Int): TailscaleLoopbackDial? {
    if (!usesUserspaceDial(host, TailscaleNodeRuntime.state.value.phase)) return null
    val bridge = AndroidTsnetBridge.loadOrNull() ?: return null
    val localPort = bridge.loopback(host.trim(), port)
    val token = bridge.sessionToken()
    if (localPort <= 0 || token.isEmpty()) return null
    return TailscaleLoopbackDial(host = "127.0.0.1", port = localPort, token = token)
}

actual fun tailscaleNodeStateFile(): File = File(tailscaleUserspaceStateDir(), "tailscaled.state")

actual fun holdTailscaleProcess() {
    if (!FileApexServices.settings.tailscaleEnabled.value) return
    val context = androidAppContextOrNull() ?: return
    ShareServerRestartCoordinator.restoreFromExternalWake(context, "tailscale")
}

actual fun openTailscaleLoginInBrowser(url: String) {
    holdTailscaleProcess()
    val launch = tailscaleLoginUrl(url) ?: return
    Handler(Looper.getMainLooper()).post {
        val activity = FileApexForegroundActivity.current()
        if (activity == null) {
            com.fileapex.platform.PlatformClipboard.openUrlInDefaultBrowser(launch)
            return@post
        }
        // No Custom Tabs session, so nothing is warmed. The tab stays in this task.
        // Android 16 will not bring FileApex's own background task in front of a separate Chrome window.
        val tabs = CustomTabsIntent.Builder().setShowTitle(true).build()
        try {
            tabs.launchUrl(activity, Uri.parse(launch))
        } catch (error: RuntimeException) {
            Log.w(TAG, "login tab failed: ${error.message}")
            com.fileapex.platform.PlatformClipboard.openUrlInDefaultBrowser(launch)
        }
    }
}

actual fun returnToAppAfterTailscaleLogin() {
    Handler(Looper.getMainLooper()).post {
        val activity = FileApexForegroundActivity.current()
        if (activity == null) {
            Log.w(TAG, "login return missed the screen")
            return@post
        }
        try {
            activity.startActivity(tailscaleReturnIntent(activity))
        } catch (error: RuntimeException) {
            Log.w(TAG, "login return failed: ${error.message}")
        }
    }
}

private const val TAG = "FileApexTsnet"

object FileApexForegroundActivity {
    private var current: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        current = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    fun current(): Activity? {
        val activity = current?.get() ?: return null
        if (activity.isDestroyed || activity.isFinishing) return null
        return activity
    }
}

private fun tailscaleReturnIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_LOGIN_RETURN_URL))
        .setClassName(context.packageName, "com.fileapex.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

private class TsnetStart(
    val ok: Boolean,
    val ip: String,
    val error: String,
    val backend: String = "",
    val keyExpired: Boolean = false
)

/**
 * Calls the gomobile class when the tsnet AAR is packaged. A missing class leaves the
 * node unlinked so the app still launches.
 */
private object AndroidTsnetBridge {
    private const val CLASS_NAME = "tsnetbridge.Tsnetbridge"

    @Volatile
    private var loaded: Class<*>? = null

    @Volatile
    private var missing = false

    fun loadOrNull(): Class<*>? {
        if (missing) return null
        loaded?.let { return it }
        val found = runCatching { Class.forName(CLASS_NAME) }.getOrNull()
        if (found == null) {
            missing = true
            return null
        }
        loaded = found
        return found
    }

    fun stopIfLoaded() {
        val cls = loaded ?: return
        runCatching { cls.getMethod("stop").invoke(null) }
    }

    fun publishLinkAddress() {
        val cls = loaded ?: return
        val method = cls.methods.firstOrNull {
            it.name == "setLinkAddress" && it.parameterTypes.size == 3
        } ?: return
        val link = androidLinkAddress()
        runCatching { method.invoke(null, link.name, link.ip, link.prefix) }
    }

    fun notifyLinkChange() {
        val cls = loaded ?: return
        val method = cls.methods.firstOrNull {
            it.name == "notifyLinkChange" && it.parameterTypes.isEmpty()
        } ?: return
        runCatching { method.invoke(null) }
    }
}

private object AndroidLinkWatch {
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun register() {
        val context = androidAppContextOrNull() ?: return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        synchronized(lock) {
            if (callback != null) return
            val created = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: android.net.LinkProperties
                ) = refresh()
            }
            runCatching { manager.registerDefaultNetworkCallback(created) }
                .onSuccess { callback = created }
        }
    }

    fun unregister() {
        val context = androidAppContextOrNull() ?: return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        synchronized(lock) {
            val current = callback ?: return
            callback = null
            runCatching { manager.unregisterNetworkCallback(current) }
        }
    }

    private fun refresh() {
        AndroidTsnetBridge.publishLinkAddress()
        AndroidTsnetBridge.notifyLinkChange()
    }
}

private data class AndroidLink(val name: String, val ip: String, val prefix: String)

private fun androidLinkAddress(): AndroidLink {
    val context = androidAppContextOrNull() ?: return AndroidLink("", "", "")
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return AndroidLink("", "", "")
    val properties = runCatching { manager.getLinkProperties(manager.activeNetwork) }.getOrNull()
        ?: return AndroidLink("", "", "")
    val name = properties.interfaceName.orEmpty()
    val link = properties.linkAddresses.firstOrNull { candidate ->
        val ip = candidate.address
        ip is Inet4Address && !ip.isLoopbackAddress && !ip.isAnyLocalAddress
    }
    val address = link?.address?.hostAddress.orEmpty()
    val prefix = link?.prefixLength?.takeIf { bits -> bits > 0 }?.toString().orEmpty()
    return AndroidLink(name, address, prefix)
}

private fun Class<*>.start(authKey: String, dir: String, hostname: String, sharePort: Int): TsnetStart {
    val method = methods.firstOrNull { it.name == "start" && it.parameterTypes.size == 4 }
        ?: return TsnetStart(ok = false, ip = "", error = "start_failed")
    val portArg: Any = if (method.parameterTypes[3] == java.lang.Long.TYPE) {
        sharePort.toLong()
    } else {
        sharePort
    }
    return try {
        val ip = method.invoke(null, authKey, dir, hostname, portArg) as? String
        val backend = backendState()
        val expired = keyExpired()
        if (ip.isNullOrBlank() && !expired && backend != "NeedsLogin" && backend != "NeedsMachineAuth") {
            TsnetStart(ok = false, ip = "", error = "no tailnet address", backend = backend, keyExpired = expired)
        } else {
            TsnetStart(
                ok = true,
                ip = ip.orEmpty().trim(),
                error = "",
                backend = backend,
                keyExpired = expired
            )
        }
    } catch (error: InvocationTargetException) {
        TsnetStart(ok = false, ip = "", error = error.targetException?.message ?: "start_failed")
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        TsnetStart(ok = false, ip = "", error = error.message ?: "start_failed")
    }
}

private fun Class<*>.loopback(host: String, port: Int): Int {
    val method = methods.firstOrNull { it.name == "loopback" && it.parameterTypes.size == 2 } ?: return 0
    val portArg: Any = if (method.parameterTypes[1] == java.lang.Long.TYPE) port.toLong() else port
    return try {
        (method.invoke(null, host, portArg) as? Number)?.toInt() ?: 0
    } catch (_: Exception) {
        0
    }
}

private fun Class<*>.sessionToken(): String {
    val method = methods.firstOrNull { it.name == "sessionToken" && it.parameterTypes.isEmpty() } ?: return ""
    return try {
        method.invoke(null) as? String ?: ""
    } catch (_: Exception) {
        ""
    }
}

private fun Class<*>.authUrl(): String = invokeString("authURL")

private fun Class<*>.selfIpv4(): String = invokeString("selfIPv4")

private fun Class<*>.invokeString(name: String): String {
    val method = methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } ?: return ""
    return try {
        (method.invoke(null) as? String).orEmpty().trim()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ""
    }
}

private fun Class<*>.backendState(): String {
    val method = methods.firstOrNull { it.name == "backendState" && it.parameterTypes.isEmpty() } ?: return ""
    return try {
        (method.invoke(null) as? String).orEmpty()
    } catch (_: Exception) {
        ""
    }
}

private fun Class<*>.keyExpired(): Boolean {
    val method = methods.firstOrNull { it.name == "keyExpired" && it.parameterTypes.isEmpty() } ?: return false
    return try {
        method.invoke(null) as? Boolean ?: false
    } catch (_: Exception) {
        false
    }
}

private fun Class<*>.logout(): String? {
    val method = methods.firstOrNull { it.name == "logout" && it.parameterTypes.isEmpty() }
        ?: return "sign_out_failed"
    return try {
        method.invoke(null)
        null
    } catch (error: InvocationTargetException) {
        val detail = error.targetException?.message.orEmpty()
        if (detail.contains("node is down")) "node_down" else "sign_out_failed"
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        "sign_out_failed"
    }
}

private fun Class<*>.clearState(dir: String): Boolean {
    val method = methods.firstOrNull { it.name == "clearState" && it.parameterTypes.size == 1 } ?: return false
    return try {
        method.invoke(null, dir)
        true
    } catch (_: Exception) {
        false
    }
}

private fun Class<*>.activeConnections(): Int {
    val method = methods.firstOrNull { it.name == "activeConnections" && it.parameterTypes.isEmpty() } ?: return 0
    return try {
        (method.invoke(null) as? Number)?.toInt() ?: 0
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        0
    }
}

private fun Class<*>.peers(): String? {
    val method = methods.firstOrNull { it.name == "peers" && it.parameterTypes.isEmpty() } ?: return null
    return try {
        (method.invoke(null) as? String).orEmpty().ifBlank { "[]" }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

actual fun embeddedTsnetEngine(): TailscaleUserspaceEngine = AndroidEmbeddedTsnetEngine()
