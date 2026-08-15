package com.fileapex.network

import com.fileapex.platform.DesktopMacTrayBridge
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
    val ipv4 = buildList {
        info.inet4Addresses.forEach { address ->
            address.hostAddress?.trim()?.let { add(it) }
        }
        info.hostAddresses?.forEach { raw ->
            add(raw.trim())
        }
    }.filter { NetworkUtils.isPrivateLanPeerHost(it) }
    NetworkUtils.selectBestLanIpv4(ipv4)?.let { return it }
    val server = info.server?.trim().orEmpty().removeSuffix(".")
    if (server.isNotEmpty()) {
        return runCatching { InetAddress.getByName(server) }
            .getOrNull()
            ?.takeIf { it is Inet4Address }
            ?.hostAddress
            ?.trim()
            ?.takeIf { NetworkUtils.isPrivateLanPeerHost(it) }
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
            println("FileApexMdnsAdvertiser: register failed - ${error.message}")
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
    private val instances = mutableListOf<JmDNS>()
    private var callback: ((String, Int, String?) -> Unit)? = null

    actual fun start(onPeerDiscovered: (host: String, port: Int, hintedDeviceId: String?) -> Unit) {
        stop()
        callback = onPeerDiscovered
        DesktopMacTrayBridge.setLanPeerDiscoveredListener { host, port, serviceName ->
            callback?.invoke(host, port, FileApexMdns.deviceIdFromServiceName(serviceName))
        }
        val addresses = desktopMdnsBrowseAddresses()
        if (addresses.isEmpty()) {
            startOnAddress(null)
            return
        }
        for (address in addresses) {
            startOnAddress(address)
        }
        if (instances.isEmpty()) {
            startOnAddress(null)
        }
    }

    actual fun stop(fast: Boolean) {
        DesktopMacTrayBridge.setLanPeerDiscoveredListener(null)
        val toClose = instances.toList()
        instances.clear()
        callback = null
        for (instance in toClose) {
            runCatching {
                if (!fast) {
                    instance.unregisterAllServices()
                }
                instance.close()
            }
        }
    }

    actual fun requestProbe() {
        for (instance in instances.toList()) {
            runCatching {
                instance.requestServiceInfo(FileApexMdns.SERVICE_TYPE, null, 1_500L)
            }.onFailure { error ->
                if (error !is IOException) {
                    println("FileApexMdnsBrowser: requestProbe failed - ${error.message}")
                }
            }
        }
    }

    private fun startOnAddress(address: InetAddress?) {
        runCatching {
            val instance = if (address != null) JmDNS.create(address) else JmDNS.create()
            val listener = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    instance.requestServiceInfo(event.type, event.name, true)
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
            instance.addServiceListener(FileApexMdns.SERVICE_TYPE, listener)
            runCatching {
                instance.requestServiceInfo(FileApexMdns.SERVICE_TYPE, null, 3_000L)
            }
            instances += instance
            val bind = address?.hostAddress ?: "default"
            println("FileApexMdnsBrowser: listening for ${FileApexMdns.SERVICE_TYPE} on $bind")
        }.onFailure { error ->
            println("FileApexMdnsBrowser: start failed - ${error.message}")
        }
    }
}

private fun desktopMdnsBrowseAddresses(): List<InetAddress> {
    val ips = linkedSetOf<String>()
    LanInterfaceBinding.primaryLanIpv4OrNull()?.let { ips.add(it) }
    ips.addAll(LanInterfaceBinding.lanBindCandidates())
    return ips.mapNotNull { ip ->
        runCatching { Inet4Address.getByName(ip) }.getOrNull()?.takeIf { it is Inet4Address }
    }
}
