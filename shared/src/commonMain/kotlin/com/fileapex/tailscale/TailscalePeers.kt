package com.fileapex.tailscale

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class TailscaleObservedPeer(
    val hostName: String = "",
    val dnsName: String = "",
    val ipv4: String = "",
    val online: Boolean = false
)

data class VerifiedTailnetAddress(
    val hostname: String,
    val ipv4: String
)

private val tailnetPeerJson = Json { ignoreUnknownKeys = true }

fun parseTailnetPeers(raw: String): List<TailscaleObservedPeer> {
    val text = raw.trim()
    if (text.isEmpty() || text == "[]") return emptyList()
    return runCatching {
        tailnetPeerJson.decodeFromString(ListSerializer(TailscaleObservedPeer.serializer()), text)
    }.getOrDefault(emptyList())
}

fun tailnetDnsLabel(name: String): String =
    name.trim().lowercase().trimEnd('.').substringBefore('.')

fun tailnetPeerMatchesDevice(
    peer: TailscaleObservedPeer,
    deviceId: String,
    deviceName: String
): Boolean {
    val label = tailnetDnsLabel(peer.hostName).ifBlank { tailnetDnsLabel(peer.dnsName) }
    if (label.isBlank()) return false
    if (deviceName.isNotBlank() && labelMatchesHostname(label, tailscaleHostname(deviceName))) return true
    return labelMatchesHostname(label, tailscaleNodeHostname(deviceId))
}

private fun labelMatchesHostname(label: String, expected: String): Boolean {
    if (expected.isBlank() || expected == "fileapex-device") return false
    if (label == expected) return true
    val suffix = label.removePrefix("$expected-")
    return suffix != label && suffix.isNotEmpty() && suffix.all { ch -> ch in '0'..'9' }
}

/**
 * A tailnet device is a FileApex peer only when its hostname matches the paired
 * device and /api/v1/identity returned that same id.
 */
fun verifiedTailnetAddress(
    deviceId: String,
    deviceName: String,
    observed: TailscaleObservedPeer?,
    identityDeviceId: String
): VerifiedTailnetAddress? {
    if (observed == null || !tailnetPeerMatchesDevice(observed, deviceId, deviceName)) return null
    if (identityDeviceId.trim() != deviceId.trim()) return null
    val ip = observed.ipv4.trim()
    if (!isTailscaleIPv4(ip)) return null
    val host = tailnetDnsLabel(observed.hostName).ifBlank { tailnetDnsLabel(observed.dnsName) }
    if (host.isBlank()) return null
    return VerifiedTailnetAddress(hostname = host, ipv4 = ip)
}
