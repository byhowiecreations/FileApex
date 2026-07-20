package com.omninode.util

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.identity.LocalDeviceNameStore
import com.omninode.data.identity.LocalIdentity
import com.omninode.platform.localIpv4Addresses

/**
 * Single source of truth for LAN interface selection and “this device” identity.
 * Prefer sorted IPv4 so advertised host stays stable across heartbeats / pairing.
 */
object NetworkUtils {
    /**
     * Preferred LAN IPv4 for advertising this device.
     * Sorted first match; falls back to loopback when none.
     */
    fun preferredLanIpv4(): String =
        localIpv4Addresses().sorted().firstOrNull() ?: "127.0.0.1"

    /** Raw platform LAN IPv4 list (unordered as returned by the platform). */
    fun lanIpv4Addresses(): List<String> = localIpv4Addresses()

    /**
     * Non-loopback `ip:port` endpoints for local-device identity in the repository.
     */
    fun shareEndpoints(identity: LocalIdentity): Set<String> {
        return lanIpv4Addresses()
            .mapNotNull { raw ->
                val ip = raw.trim()
                if (ip.isEmpty() || ip == "127.0.0.1" || ip == "0.0.0.0") {
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
        return PairedDeviceEntity(
            deviceId = identity.deviceId,
            deviceName = deviceName,
            lastKnownIp = preferredLanIpv4(),
            port = identity.sharePort,
            publicKeyHash = "",
            rootPath = identity.rootPath
        )
    }
}
