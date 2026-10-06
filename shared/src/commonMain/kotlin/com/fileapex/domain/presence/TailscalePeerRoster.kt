package com.fileapex.domain.presence

import com.fileapex.data.device.DeviceRepository
import com.fileapex.network.FileApexClient
import com.fileapex.tailscale.TailscaleNodeRuntime
import com.fileapex.tailscale.isTailscaleIPv4
import com.fileapex.tailscale.tailnetDnsLabel
import com.fileapex.tailscale.tailnetPeerMatchesDevice
import com.fileapex.tailscale.verifiedTailnetAddress
import com.fileapex.util.cancellableCatching

/**
 * Writes a tailnet address onto a paired row only after hostname and identity agree.
 * Tailnet devices that are not paired are ignored.
 */
class TailscalePeerRoster(
    private val repository: DeviceRepository,
    private val client: FileApexClient
) {
    suspend fun refresh() {
        if (!tailnetNodeUp()) return
        val observed = TailscaleNodeRuntime.observedTailnetPeers() ?: return
        val devices = repository.listDevices()
        for (device in devices) {
            if (device.port <= 0) continue
            val match = observed.firstOrNull { peer ->
                tailnetPeerMatchesDevice(peer, device.deviceId, device.deviceName) &&
                    isTailscaleIPv4(peer.ipv4)
            }
            if (match == null) {
                if (device.tailnetIpv4.isNotBlank() || device.tailnetHostname.isNotBlank()) {
                    repository.clearTailnet(device.deviceId)
                }
                continue
            }
            val host = tailnetDnsLabel(match.hostName).ifBlank { tailnetDnsLabel(match.dnsName) }
            if (device.tailnetIpv4 == match.ipv4.trim() &&
                device.tailnetHostname.isNotBlank() &&
                device.tailnetHostname == host
            ) {
                continue
            }
            val state = cancellableCatching {
                client.fetchPeerNodeState(
                    host = match.ipv4.trim(),
                    port = device.port,
                    timeoutMs = LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS
                )
            }.getOrNull() ?: continue
            val verified = verifiedTailnetAddress(
                device.deviceId,
                device.deviceName,
                match,
                state.deviceId
            )
            if (verified == null) {
                repository.clearTailnet(device.deviceId)
            } else {
                repository.recordVerifiedTailnet(device.deviceId, verified.hostname, verified.ipv4)
            }
        }
    }
}
