package com.fileapex.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.fileapex.domain.presence.PresenceForegroundRefresh
import com.fileapex.platform.androidApplicationContextOrNull
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

actual object FileApexMdnsBrowser {
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pendingLockReleaseRunnable: Runnable? = null
    private var callback: ((String, Int, String?) -> Unit)? = null
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastWindowStartMs = 0L
    private var restartBudgetStartMs = 0L
    private var restartCount = 0
    private var pendingRestartRunnable: Runnable? = null
    private var pendingWindowEndRunnable: Runnable? = null
    private val resolvedThisWindow: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Synchronized
    actual fun start(onPeerDiscovered: (host: String, port: Int, hintedDeviceId: String?) -> Unit) {
        stop()
        callback = onPeerDiscovered
        openWindow()
    }

    @Synchronized
    actual fun stop(fast: Boolean) {
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRestartRunnable = null
        releaseMulticastLock()
        closeWindow()
        nsdManager = null
        callback = null
    }

    @Synchronized
    actual fun requestProbe() {
        if (callback == null || discoveryListener != null) return
        val gapMs = if (PresenceForegroundRefresh.isForeground.value) {
            PROBE_FOREGROUND_GAP_MS
        } else {
            PROBE_BACKGROUND_GAP_MS
        }
        if (TimeUtils.now() - lastWindowStartMs < gapMs) return
        openWindow()
    }

    @Synchronized
    private fun openWindow() {
        if (discoveryListener != null) return
        val context = androidApplicationContextOrNull() ?: return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        acquireLegacyMulticastLockIfNeeded(context)
        nsdManager = manager
        lastWindowStartMs = TimeUtils.now()
        resolvedThisWindow.clear()
        val listener = createDiscoveryListener()
        discoveryListener = listener
        runCatching {
            manager.discoverServices(FileApexMdns.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            println("FileApexMdnsBrowser: discoverServices failed - ${error.message}")
            discoveryListener = null
            scheduleDiscoveryRestart()
            return
        }
        val windowEnd = Runnable { closeWindow() }
        pendingWindowEndRunnable = windowEnd
        mainHandler.postDelayed(windowEnd, DISCOVERY_WINDOW_MS)
    }

    @Synchronized
    private fun closeWindow() {
        pendingWindowEndRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingWindowEndRunnable = null
        val manager = nsdManager
        val listener = discoveryListener
        discoveryListener = null
        if (manager != null && listener != null) {
            runCatching { manager.stopServiceDiscovery(listener) }
                .onFailure { println("FileApexMdnsBrowser: stopServiceDiscovery failed - ${it.message}") }
        }
    }

    // API 31+ NsdService manages multicast internally; legacy platforms use a 10s burst.
    private fun acquireLegacyMulticastLockIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        pendingLockReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingLockReleaseRunnable = null
        if (multicastLock == null) {
            cancellableCatching {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("fileapex:mdns_browser")?.apply {
                    setReferenceCounted(false)
                }
            }
        }
        cancellableCatching {
            multicastLock?.let { lock ->
                if (!lock.isHeld) {
                    lock.acquire()
                }
                val releaseRunnable = Runnable { releaseMulticastLock() }
                pendingLockReleaseRunnable = releaseRunnable
                mainHandler.postDelayed(releaseRunnable, LEGACY_MULTICAST_BURST_MS)
            }
        }
    }

    private fun releaseMulticastLock() {
        pendingLockReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingLockReleaseRunnable = null
        runCatching {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        }
        multicastLock = null
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                println("FileApexMdnsBrowser: discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val manager = nsdManager ?: return
                if (!serviceInfo.serviceName.startsWith(FileApexMdns.SERVICE_NAME_PREFIX)) return
                if (!resolvedThisWindow.add(serviceInfo.serviceName)) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    resolveWithServiceInfoCallback(manager, serviceInfo)
                } else {
                    resolveLegacy(manager, serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                println("FileApexMdnsBrowser: startDiscoveryFailed code=$errorCode")
                synchronized(FileApexMdnsBrowser) { discoveryListener = null }
                scheduleDiscoveryRestart()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
    }

    @Synchronized
    private fun scheduleDiscoveryRestart() {
        val now = TimeUtils.now()
        if (now - restartBudgetStartMs > RESTART_BUDGET_WINDOW_MS) {
            restartBudgetStartMs = now
            restartCount = 0
        }
        restartCount++
        if (restartCount > MAX_DISCOVERY_RESTART_ATTEMPTS) {
            println("FileApexMdnsBrowser: discovery restart budget exhausted")
            return
        }
        val attempt = restartCount
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            synchronized(FileApexMdnsBrowser) {
                pendingRestartRunnable = null
                if (callback != null) {
                    println("FileApexMdnsBrowser: restarting discovery (attempt $attempt)")
                    openWindow()
                }
            }
        }
        pendingRestartRunnable = runnable
        mainHandler.postDelayed(runnable, DISCOVERY_RESTART_BASE_MS * attempt)
    }

    // Pre-API 34 resolve path on devices below Android 14.
    private fun resolveLegacy(manager: NsdManager, serviceInfo: NsdServiceInfo) {
        runCatching {
            val listenerClass = Class.forName("android.net.nsd.NsdManager\$ResolveListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onResolveFailed" -> println("FileApexMdnsBrowser: resolve failed code=${args?.getOrNull(1)}")
                    "onServiceResolved" -> (args?.getOrNull(0) as? NsdServiceInfo)?.let { deliverResolved(it) }
                }
                null
            }
            manager.javaClass.getMethod("resolveService", NsdServiceInfo::class.java, listenerClass)
                .invoke(manager, serviceInfo, proxy)
        }
    }

    private fun resolveWithServiceInfoCallback(manager: NsdManager, serviceInfo: NsdServiceInfo) {
        val serviceCallback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                println("FileApexMdnsBrowser: callback registration failed code=$errorCode")
            }

            override fun onServiceUpdated(info: NsdServiceInfo) {
                deliverResolved(info)
                runCatching { manager.unregisterServiceInfoCallback(this) }
            }

            override fun onServiceLost() {
                runCatching { manager.unregisterServiceInfoCallback(this) }
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        manager.registerServiceInfoCallback(serviceInfo, resolveExecutor, serviceCallback)
    }

    private fun deliverResolved(info: NsdServiceInfo) {
        val host = hostFromServiceInfo(info)
        if (host.isEmpty() || info.port <= 0) return
        val hintedId = FileApexMdns.deviceIdFromServiceName(info.serviceName)
        callback?.invoke(host, info.port, hintedId)
    }

    private fun hostFromServiceInfo(info: NsdServiceInfo): String {
        val addresses = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                addAll(
                    info.hostAddresses.mapNotNull { address ->
                        address.hostAddress?.trim()?.substringBefore('%')
                    }
                )
            } else {
                hostFromServiceInfoLegacy(info).takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }.filter { NetworkUtils.isPrivateLanPeerHost(it) }
        return NetworkUtils.selectBestLanIpv4(addresses).orEmpty()
    }

    private fun hostFromServiceInfoLegacy(info: NsdServiceInfo): String {
        val hostObj = runCatching {
            info.javaClass.getMethod("getHost").invoke(info) as? java.net.InetAddress
        }.getOrNull()
        return hostObj?.hostAddress?.trim().orEmpty()
    }

    private const val MAX_DISCOVERY_RESTART_ATTEMPTS = 5
    private const val RESTART_BUDGET_WINDOW_MS = 10 * 60_000L
    private const val DISCOVERY_WINDOW_MS = 15_000L
    private const val PROBE_FOREGROUND_GAP_MS = 5_000L
    private const val PROBE_BACKGROUND_GAP_MS = 60_000L
    private const val DISCOVERY_RESTART_BASE_MS = 2_000L
    private const val LEGACY_MULTICAST_BURST_MS = 10_000L
}
