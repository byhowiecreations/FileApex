package com.fileapex.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.fileapex.platform.androidApplicationContextOrNull
import java.util.concurrent.Executors

actual object FileApexMdnsBrowser {
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var callback: ((String, Int, String?) -> Unit)? = null
    private val resolveExecutor = Executors.newSingleThreadExecutor()

    actual fun start(onPeerDiscovered: (host: String, port: Int, hintedDeviceId: String?) -> Unit) {
        stop()
        callback = onPeerDiscovered
        val context = androidApplicationContextOrNull() ?: return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                println("FileApexMdnsBrowser: discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
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
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching {
            manager.discoverServices(FileApexMdns.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            println("FileApexMdnsBrowser: discoverServices failed — ${error.message}")
        }
    }

    actual fun stop(fast: Boolean) {
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
        val manager = nsdManager ?: return
        val listener = discoveryListener ?: return
        runCatching {
            manager.stopServiceDiscovery(listener)
            manager.discoverServices(FileApexMdns.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            println("FileApexMdnsBrowser: requestProbe failed — ${error.message}")
        }
    }

    /** Pre-API 34 resolve path — still the platform API on those devices. */
    @Suppress("DEPRECATION")
    private fun resolveLegacy(manager: NsdManager, serviceInfo: NsdServiceInfo) {
        manager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    println("FileApexMdnsBrowser: resolve failed code=$errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    deliverResolved(info)
                }
            }
        )
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return info.hostAddresses.firstOrNull()?.hostAddress?.trim().orEmpty()
        }
        return hostFromServiceInfoLegacy(info)
    }

    @Suppress("DEPRECATION")
    private fun hostFromServiceInfoLegacy(info: NsdServiceInfo): String =
        info.host.hostAddress?.trim().orEmpty()
}
