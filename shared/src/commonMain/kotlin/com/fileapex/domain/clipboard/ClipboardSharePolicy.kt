package com.fileapex.domain.clipboard

import com.fileapex.util.NetworkUtils

object ClipboardSharePolicy {
    const val PAYLOAD_TTL_MS = 15_000L
    const val RETRY_INTERVAL_MS = 4_000L
    const val INIT_GUARD_MS = 1_500L
    const val FCM_MAX_DATA_CHARS = 3_000
    const val ANDROID_FOREGROUND_CLIP_POLL_MS = 700L
    /** Matches macOS TrayBridge `Timer(timeInterval: 0.45)` pasteboard watch. */
    const val DESKTOP_CLIPBOARD_WATCH_MS = 450L
    // MIUI/Android: onResume reads clipboard before window focus → ClipboardService deny.
    val ANDROID_FOCUS_CLIP_RETRY_MS = longArrayOf(350L, 700L, 1_200L)

    fun showSendClipboardNotificationAction(
        sharingEnabled: Boolean,
        sendClipboardNotificationEnabled: Boolean
    ): Boolean = sharingEnabled && sendClipboardNotificationEnabled

    data class PeerRef(
        val deviceId: String,
        val isDesktop: Boolean
    )

    data class DeviceCounts(
        val totalPhones: Int,
        val totalDesktops: Int
    ) {
        val totalCount: Int get() = totalPhones + totalDesktops
        val isOnePhoneAndOneDesktop: Boolean get() = totalPhones == 1 && totalDesktops == 1
        val hasThreeOrMoreDevices: Boolean get() = totalCount >= 3 && (totalPhones >= 2 || totalDesktops >= 2)
    }

    fun calculateDeviceCounts(
        selfIsAndroid: Boolean,
        peers: Collection<PeerRef>
    ): DeviceCounts {
        val selfPhones = if (selfIsAndroid) 1 else 0
        val selfDesktops = if (selfIsAndroid) 0 else 1
        val peerPhones = peers.count { !it.isDesktop }
        val peerDesktops = peers.count { it.isDesktop }
        return DeviceCounts(
            totalPhones = selfPhones + peerPhones,
            totalDesktops = selfDesktops + peerDesktops
        )
    }

    fun resolveAutoDefaultTargetId(
        selfIsAndroid: Boolean,
        peers: Collection<PeerRef>
    ): String? {
        val counts = calculateDeviceCounts(selfIsAndroid, peers)
        if (!counts.isOnePhoneAndOneDesktop) return null
        return peers.firstOrNull()?.deviceId?.trim()?.ifBlank { null }
    }

    fun shouldPromptTargetConfiguration(
        isConfigured: Boolean,
        selfIsAndroid: Boolean,
        peers: Collection<PeerRef>
    ): Boolean {
        if (isConfigured) return false
        val counts = calculateDeviceCounts(selfIsAndroid, peers)
        return counts.hasThreeOrMoreDevices
    }

    fun resolveTargetIds(
        mode: ClipboardShareMode,
        pairedDeviceIds: Collection<String>,
        selectedDeviceIds: Set<String>,
        explicitDeviceId: String? = null
    ): Set<String> {
        val explicit = explicitDeviceId?.trim().orEmpty()
        if (explicit.isNotEmpty()) {
            return setOf(explicit)
        }
        val paired = pairedDeviceIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return when (mode) {
            ClipboardShareMode.UNSET -> emptySet()
            ClipboardShareMode.ALL -> paired
            ClipboardShareMode.SPECIFIC -> paired.intersect(selectedDeviceIds.map { it.trim() }.toSet())
        }
    }

    fun resolveBroadcastTargets(
        mode: ClipboardShareMode,
        peers: Collection<PeerRef>,
        selectedDeviceIds: Set<String>,
        desktopPeersOnly: Boolean
    ): Set<String> {
        val eligible = peers
            .filter { !desktopPeersOnly || it.isDesktop }
            .map { it.deviceId.trim() }
            .filter { it.isNotEmpty() }
        return resolveTargetIds(
            mode = mode,
            pairedDeviceIds = eligible,
            selectedDeviceIds = selectedDeviceIds
        )
    }

    fun isExpired(capturedAtEpochMs: Long, nowEpochMs: Long): Boolean {
        if (capturedAtEpochMs <= 0L) return true
        return nowEpochMs - capturedAtEpochMs >= PAYLOAD_TTL_MS
    }

    fun canUseLocalLan(
        lanConnected: Boolean,
        peerHost: String,
        localBindIps: Collection<String>
    ): Boolean {
        if (!lanConnected) return false
        if (!NetworkUtils.isPrivateLanPeerHost(peerHost)) return false
        if (localBindIps.isEmpty()) return false
        return true
    }

    fun canUseCellularFcm(
        viaCellularEnabled: Boolean,
        selfIsAndroid: Boolean,
        peerIsAndroid: Boolean,
        googleLinked: Boolean
    ): Boolean {
        return viaCellularEnabled && selfIsAndroid && peerIsAndroid && googleLinked
    }

    fun parseDeviceIdSet(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun encodeDeviceIdSet(ids: Set<String>): String =
        ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",")
}
