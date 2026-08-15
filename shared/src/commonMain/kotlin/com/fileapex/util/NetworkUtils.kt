package com.fileapex.util

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.platform.activeLanIpv4Addresses
import com.fileapex.platform.localDeviceHardwareProfile
import com.fileapex.platform.localIpv4Addresses

/**
 * LAN interface selection and this-device identity.
 * Prefer stable, routable LAN IPv4 (192.168 then 10.x then 172.16–31) across heartbeats / pairing.
 */
object NetworkUtils {
    /**
     * Preferred LAN IPv4 for advertising this device.
     * Prefers the active default-routed interface, then falls back to raw platform addresses.
     */
    fun preferredLanIpv4(): String =
        selectBestLanIpv4(activeLanIpv4Addresses().filter { isUsableLanIpv4(it) })
            ?: selectBestLanIpv4(lanIpv4Addresses())
            ?: "127.0.0.1"

    /**
     * Ordered bind candidates for force-routed peer TCP/UDP — active Wi‑Fi/Ethernet only.
     * Never falls back to cellular or inactive interfaces.
     */
    fun lanBindCandidates(): List<String> {
        val active = activeLanIpv4Addresses().filter { isUsableLanIpv4(it) }
        if (active.isEmpty()) {
            return emptyList()
        }
        val primary = selectBestLanIpv4(active) ?: active.first()
        return buildList {
            add(primary)
            addAll(active.filter { it != primary })
        }
    }

    /**
     * Bind the local address on the peer's /24 first so a multi-homed Mac (192.168 + 172.16)
     * does not pin the wrong NIC before unbound fallback.
     */
    fun orderBindCandidatesForPeer(peerHost: String): List<String> {
        val candidates = lanBindCandidates()
        val dest = peerHost.trim()
        val sameSubnet = candidates.filter { sameIpv4Slash24(it, dest) }
        val rest = candidates.filter { ip -> sameSubnet.none { it == ip } }
        return sameSubnet + rest
    }

    fun sameIpv4Slash24(left: String, right: String): Boolean {
        val a = left.trim().split('.')
        val b = right.trim().split('.')
        if (a.size != 4 || b.size != 4) return false
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]
    }

    fun lanIpv4Addresses(): List<String> = localIpv4Addresses()

    fun selectBestLanIpv4(candidates: Collection<String>): String? =
        candidates
            .asSequence()
            .map { it.trim() }
            .filter { isUsableLanIpv4(it) }
            .minWithOrNull(lanIpv4PreferenceOrder())

    fun isPrivateLanPeerHost(host: String): Boolean {
        val cleaned = host.trim()
        if (!isUsableLanIpv4(cleaned)) {
            return false
        }
        return cleaned.startsWith("192.168.") ||
            cleaned.startsWith("10.") ||
            isPrivate172(cleaned)
    }

    fun isUsableLanIpv4(ip: String): Boolean {
        val cleaned = ip.trim()
        if (cleaned.isEmpty() || cleaned == "127.0.0.1" || cleaned == "0.0.0.0") {
            return false
        }
        if (cleaned.startsWith("169.254.")) {
            return false
        }
        return true
    }

    private fun lanIpv4PreferenceOrder(): Comparator<String> =
        compareBy<String> { lanPriorityTier(it) }
            .thenBy { it }

    private fun lanPriorityTier(ip: String): Int = when {
        ip.startsWith("192.168.") -> 0
        ip.startsWith("10.") -> 1
        isPrivate172(ip) -> 2
        else -> 3
    }

    private fun isPrivate172(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4 || parts[0] != "172") return false
        val second = parts[1].toIntOrNull() ?: return false
        return second in 16..31
    }

    /**
     * Non-loopback `ip:port` endpoints for local-device identity in the repository.
     */
    fun shareEndpoints(identity: LocalIdentity): Set<String> {
        return lanIpv4Addresses()
            .mapNotNull { raw ->
                val ip = raw.trim()
                if (!isUsableLanIpv4(ip)) {
                    null
                } else {
                    "$ip:${identity.sharePort}"
                }
            }
            .toSet()
    }

    /**
     * Stable [PairedDeviceEntity] representing this device for pairing / presence.
     */
    fun selfAsPairedDevice(
        identity: LocalIdentity,
        deviceName: String = LocalDeviceNameStore.current().ifBlank { identity.deviceName }
    ): PairedDeviceEntity {
        val hardware = localDeviceHardwareProfile()
        return PairedDeviceEntity(
            deviceId = identity.deviceId,
            deviceName = deviceName,
            lastKnownIp = preferredLanIpv4(),
            port = identity.sharePort,
            publicKeyHash = "",
            rootPath = identity.rootPath,
            platform = hardware.platform,
            os = hardware.os,
            deviceMake = hardware.deviceMake,
            deviceModel = hardware.deviceModel
        )
    }
}
