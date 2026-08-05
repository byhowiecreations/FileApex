package com.fileapex.network

import com.fileapex.util.NetworkUtils
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

private fun desktopMdnsBindAddress(): InetAddress? {
    val host = LanInterfaceBinding.primaryLanIpv4OrNull()
        ?: LanInterfaceBinding.lanBindCandidates().firstOrNull()
    if (host.isNullOrBlank()) return null
    return runCatching { Inet4Address.getByName(host) }.getOrNull()
}

private fun resolveMdnsIpv4(info: ServiceInfo): String? {
    info.inet4Addresses.firstOrNull()?.hostAddress?.trim()
        ?.takeIf { NetworkUtils.isUsableLanIpv4(it) }
        ?.let { return it }
    info.hostAddresses?.forEach { raw ->
        val host = raw.trim()
        if (NetworkUtils.isUsableLanIpv4(host)) {
            return host
        }
    }
    val server = info.server?.trim().orEmpty().removeSuffix(".")
    if (server.isNotEmpty()) {
        return runCatching { InetAddress.getByName(server) }
            .getOrNull()
            ?.takeIf { it is Inet4Address }
            ?.hostAddress
            ?.trim()
            ?.takeIf { NetworkUtils.isUsableLanIpv4(it) }
    }
    return null
}

actual object FileApexMdnsAdvertiser {
    private var jmdns: JmDNS? = null
    private var registeredName: String? = null

    actual fun start(port: Int, deviceId: String) {
        stop()
        val bindAddress = desktopMdnsBindAddress() ?: return
        runCatching {
            val instance = JmDNS.create(bindAddress)
            val name = FileApexMdns.serviceNameFor(deviceId)
            instance.registerService(
                ServiceInfo.create(
                    FileApexMdns.SERVICE_TYPE,
                    name,
                    port,
                    0,
                    0,
                    emptyMap<String, String>()
                )
            )
            jmdns = instance
            registeredName = name
            println("FileApexMdnsAdvertiser: registered $name on ${bindAddress.hostAddress}:$port")
        }.onFailure { error ->
            println("FileApexMdnsAdvertiser: register failed — ${error.message}")
        }
    }

    actual fun stop(fast: Boolean) {
        val instance = jmdns ?: return
        jmdns = null
        registeredName = null
        if (fast) return
        runCatching {
            instance.unregisterAllServices()
            instance.close()
        }
    }

}

actual object FileApexMdnsBrowser {
    private var jmdns: JmDNS? = null
    private var callback: ((String, Int, String?) -> Unit)? = null
    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            jmdns?.requestServiceInfo(event.type, event.name, true)
        }

        override fun serviceRemoved(event: ServiceEvent) = Unit

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            if (!info.name.startsWith(FileApexMdns.SERVICE_NAME_PREFIX)) return
            val host = resolveMdnsIpv4(info) ?: return
            if (info.port <= 0) return
            callback?.invoke(host, info.port, FileApexMdns.deviceIdFromServiceName(info.name))
        }
    }

    actual fun start(onPeerDiscovered: (host: String, port: Int, hintedDeviceId: String?) -> Unit) {
        stop()
        callback = onPeerDiscovered
        runCatching {
            // Browse on the default JVM mDNS socket — interface-bound instances miss some
            // Android/Bonjour advertisers that resolve only via .local hostnames.
            val instance = JmDNS.create()
            instance.addServiceListener(FileApexMdns.SERVICE_TYPE, listener)
            jmdns = instance
            runCatching {
                instance.requestServiceInfo(FileApexMdns.SERVICE_TYPE, null, 3_000L)
            }
            println("FileApexMdnsBrowser: listening for ${FileApexMdns.SERVICE_TYPE}")
        }.onFailure { error ->
            println("FileApexMdnsBrowser: start failed — ${error.message}")
        }
    }

    actual fun stop(fast: Boolean) {
        val instance = jmdns
        jmdns = null
        callback = null
        if (instance == null) return
        if (fast) return
        runCatching {
            instance.removeServiceListener(FileApexMdns.SERVICE_TYPE, listener)
            instance.close()
        }
    }

    actual fun requestProbe() {
        val instance = jmdns ?: return
        runCatching {
            instance.requestServiceInfo(FileApexMdns.SERVICE_TYPE, null, 1_500L)
        }.onFailure { error ->
            if (error !is IOException) {
                println("FileApexMdnsBrowser: requestProbe failed — ${error.message}")
            }
        }
    }
}
