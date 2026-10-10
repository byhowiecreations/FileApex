package com.fileapex.domain.notifications

import com.fileapex.data.db.isDockerNode
import com.fileapex.di.FileApexServices
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Computer side of the notification companion handshake: the one phone whose notifications this computer
 * wants. It is the phone Simple Home shows, and only while Simple Home is on screen.
 */
object NotificationCompanion {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val desired = AtomicReference("")

    /** Id of the phone being shown, or empty when no Simple Home is on screen. */
    fun desiredDeviceId(): String = desired.get()

    fun isCompanion(deviceId: String): Boolean {
        val id = deviceId.trim()
        return id.isNotEmpty() && id == desired.get()
    }

    /** Sets the phone to listen to (empty to pause) and tells the phones that gained or lost the grant at once. */
    fun setDesired(deviceId: String) {
        val next = deviceId.trim()
        val previous = desired.getAndSet(next)
        if (previous == next) return
        NotificationDebugLog.log("companion ${NotificationDebugLog.short(previous)} -> ${NotificationDebugLog.short(next)}")
        scope.launch {
            if (previous.isNotEmpty()) sync(previous)
            if (next.isNotEmpty()) sync(next)
        }
    }

    /** Immediate poll carrying the current answer for [deviceId], instead of waiting for the next one. */
    private suspend fun sync(deviceId: String) {
        val device = FileApexServices.deviceRepository.getDevice(deviceId) ?: return
        if (!canBeGranted(device.isDockerNode(), device.os, device.platform, device.tlsPin)) {
            NotificationDebugLog.log("grant skipped for ${NotificationDebugLog.short(deviceId)}: docker=${device.isDockerNode()} os=${device.os} pinned=${device.tlsPin.isNotEmpty()}")
            return
        }
        val endpoint = FileApexServices.presenceMonitor.resolveOutboundEndpoint(device)
        if (endpoint == null) {
            NotificationDebugLog.log("grant skipped for ${NotificationDebugLog.short(deviceId)}: no endpoint")
            return
        }
        val reached = cancellableCatching {
            FileApexServices.client.pingHealth(endpoint.host, endpoint.port, companion = isCompanion(deviceId))
        }.getOrNull()
        NotificationDebugLog.log("grant poll to ${NotificationDebugLog.short(deviceId)} value=${isCompanion(deviceId)} reached=$reached")
    }

    /** `1`/`0` for a phone the poll can tell, null for one that is never sent a grant. */
    fun pollValueFor(deviceId: String, isDocker: Boolean, os: String, platform: String, tlsPin: String): Boolean? =
        if (canBeGranted(isDocker, os, platform, tlsPin)) isCompanion(deviceId) else null

    /** Android phones on pinned TLS only; Docker nodes, computers and unpinned peers are never granted. */
    internal fun canBeGranted(isDocker: Boolean, os: String, platform: String, tlsPin: String): Boolean =
        !isDocker && PeerPlatform.isAndroid(os, platform) && tlsPin.isNotEmpty()
}
