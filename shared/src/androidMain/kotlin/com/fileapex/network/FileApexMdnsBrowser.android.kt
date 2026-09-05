package com.fileapex.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.fileapex.platform.androidApplicationContextOrNull
import com.fileapex.util.NetworkUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

actual object FileApexMdnsBrowser {
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var callback: ((String, Int, String?) -> Unit)? = null
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discoveryRestartAttempts = AtomicInteger(0)
    private var pendingRestartRunnable: Runnable? = null

    actual fun start(onPeerDiscovered: (host: String, port: Int, hintedDeviceId: String?) -> Unit) {
        stop()
        callback = onPeerDiscovered
        discoveryRestartAttempts.set(0)
        beginDiscovery()
    }

    actual fun stop(fast: Boolean) {
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRestartRunnable = null
        val manager = nsdManager
        val listener = discoveryListener
        if (manager != null && listener != null) {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        nsdManager = null
        discoveryListener = null
        callback = null
    }

    actual fun requestProbe() {
        val manager = nsdManager ?: run {
            val savedCallback = callback
            if (savedCallback != null) {
                start(savedCallback)
            }
            return
        }
        val listener = discoveryListener ?: return
        runCatching {
            manager.stopServiceDiscovery(listener)
            manager.discoverServices(FileApexMdns.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            println("FileApexMdnsBrowser: requestProbe failed - ${error.message}")
            scheduleDiscoveryRestart()
        }
    }

    private fun beginDiscovery() {
        val context = androidApplicationContextOrNull() ?: return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        val listener = createDiscoveryListener()
        discoveryListener = listener
        runCatching {
            manager.discoverServices(FileApexMdns.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            println("FileApexMdnsBrowser: discoverServices failed - ${error.message}")
            scheduleDiscoveryRestart()
        }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discoveryRestartAttempts.set(0)
                println("FileApexMdnsBrowser: discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val manager = nsdManager ?: return
                if (!serviceInfo.serviceName.startsWith(FileApexMdns.SERVICE_NAME_PREFIX)) return
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
                scheduleDiscoveryRestart()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
    }

    private fun scheduleDiscoveryRestart() {
        val attempt = discoveryRestartAttempts.incrementAndGet()
        if (attempt > MAX_DISCOVERY_RESTART_ATTEMPTS) {
            println("FileApexMdnsBrowser: discovery restart budget exhausted")
            return
        }
        val delayMs = DISCOVERY_RESTART_BASE_MS * attempt
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingRestartRunnable = null
            val savedCallback = callback ?: return@Runnable
            println("FileApexMdnsBrowser: restarting discovery (attempt $attempt)")
            stop()
            start(savedCallback)
        }
        pendingRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
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
    private const val DISCOVERY_RESTART_BASE_MS = 2_000L
}
