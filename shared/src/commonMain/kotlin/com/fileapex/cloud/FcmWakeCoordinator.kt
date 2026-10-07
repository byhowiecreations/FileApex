package com.fileapex.cloud

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cloud-linked peer wake — dispatches silent FCM data pushes so Doze'd Android nodes
 * run a targeted health probe without foreground UI.
 */
object FcmWakeCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Receiving a wake makes a device do work that can end in another wake. Without a floor,
     * two linked devices answering each other exhaust the FCM quota and keep both busy.
     */
    private const val PRESENCE_WAKE_MIN_INTERVAL_MS = 20_000L
    private val lastPresenceWakeToAllMs = AtomicLong(0L)
    private val lastPresenceWakeToDeviceMs = ConcurrentHashMap<String, Long>()

    private fun allowWakeToAll(): Boolean {
        val now = TimeUtils.now()
        while (true) {
            val last = lastPresenceWakeToAllMs.get()
            if (now - last < PRESENCE_WAKE_MIN_INTERVAL_MS) return false
            if (lastPresenceWakeToAllMs.compareAndSet(last, now)) return true
        }
    }

    private fun allowWakeToDevice(deviceId: String): Boolean {
        val now = TimeUtils.now()
        var allowed = false
        lastPresenceWakeToDeviceMs.compute(deviceId) { _, last ->
            if (last == null || now - last >= PRESENCE_WAKE_MIN_INTERVAL_MS) {
                allowed = true
                now
            } else {
                last
            }
        }
        return allowed
    }

    fun dispatchPresenceWakeToDevice(deviceId: String) {
        if (deviceId.isBlank()) return
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!FcmWakeBackend.isConfigured()) return
        if (!allowWakeToDevice(deviceId)) return
        val selfId = loadLocalIdentity().deviceId
        scope.launch {
            runCatching {
                val target = GoogleLinkCoordinator.fcmTargetsForDevices(selfId, listOf(deviceId))
                    .firstOrNull() ?: return@runCatching
                FcmWakeBackend.sendPresenceWake(
                    targetFcmToken = target.fcmToken,
                    sourceDeviceId = selfId
                )
            }
        }
    }

    fun dispatchPresenceWakeToLinkedPeers() {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!FcmWakeBackend.isConfigured()) return
        if (!allowWakeToAll()) return
        val selfId = loadLocalIdentity().deviceId
        scope.launch {
            runCatching {
                val targets = GoogleLinkCoordinator.linkedPeerFcmTargets(selfId)
                for (target in targets) {
                    FcmWakeBackend.sendPresenceWake(
                        targetFcmToken = target.fcmToken,
                        sourceDeviceId = selfId
                    )
                }
            }.onFailure { error ->
                println("FcmWakeCoordinator: FCM wake dispatch failed - ${error.message}")
            }
        }
    }

    fun dispatchNoteWakeToLinkedPeers(
        noteId: String,
        content: String?,
        driveFileId: String?,
        checksum: String?,
        attachmentName: String? = null,
        attachmentSizeBytes: Long = 0L
    ) {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!FcmWakeBackend.isConfigured()) return
        val selfId = loadLocalIdentity().deviceId
        scope.launch {
            runCatching {
                val targets = GoogleLinkCoordinator.linkedPeerFcmTargets(selfId)
                for (target in targets) {
                    FcmWakeBackend.sendNoteWake(
                        targetFcmToken = target.fcmToken,
                        sourceDeviceId = selfId,
                        noteId = noteId,
                        content = content,
                        driveFileId = driveFileId,
                        checksum = checksum,
                        attachmentName = attachmentName,
                        attachmentSizeBytes = attachmentSizeBytes
                    )
                }
            }.onFailure { error ->
                println("FcmWakeCoordinator: Note FCM wake dispatch failed - ${error.message}")
            }
        }
    }

    /** Called from FCM receiver after parsing [FcmWakeProtocol]. */
    fun isPresenceWake(type: String?): Boolean = type == FcmWakeProtocol.TYPE_PRESENCE_WAKE

    fun isDiagnosticsWake(type: String?): Boolean = type == FcmWakeProtocol.TYPE_DIAGNOSTICS_REQUEST

    fun isNoteInline(type: String?): Boolean = type == FcmWakeProtocol.TYPE_NOTE_INLINE

    fun isNoteSync(type: String?): Boolean = type == FcmWakeProtocol.TYPE_NOTE_SYNC

    fun isNoteDelete(type: String?, action: String? = null): Boolean =
        type == FcmWakeProtocol.TYPE_NOTE_DELETE ||
            type == FcmWakeProtocol.TYPE_NOTE_RETRACT ||
            action == FcmWakeProtocol.ACTION_RETRACT_MESSAGE

    fun isDriveRelay(type: String?): Boolean = type == FcmWakeProtocol.TYPE_DRIVE_RELAY

    fun isClipboardShare(type: String?): Boolean = type == FcmWakeProtocol.TYPE_CLIPBOARD_SHARE
    fun isClipboardOptInRequest(type: String?): Boolean = type == FcmWakeProtocol.TYPE_CLIPBOARD_OPT_IN_REQUEST

    suspend fun dispatchClipboardShare(
        targetDeviceId: String,
        senderPublicKey: String,
        ciphertext: String,
        capturedAtEpochMs: Long,
        senderDeviceName: String
    ): Boolean {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return false
        if (!FcmWakeBackend.isConfigured()) return false
        val selfId = loadLocalIdentity().deviceId
        return runCatching {
            val targets = GoogleLinkCoordinator.fcmTargetsForDevices(selfId, listOf(targetDeviceId))
            val target = targets.firstOrNull() ?: return false
            FcmWakeBackend.sendClipboardShare(
                targetFcmToken = target.fcmToken,
                sourceDeviceId = selfId,
                senderDeviceName = senderDeviceName,
                senderPublicKey = senderPublicKey,
                ciphertext = ciphertext,
                capturedAtEpochMs = capturedAtEpochMs
            )
        }.getOrElse { error ->
            println("FcmWakeCoordinator: clipboard FCM failed - ${error.message}")
            false
        }
    }

    suspend fun dispatchClipboardOptIn(
        targetDeviceId: String,
        senderDeviceName: String,
        pendingPayload: com.fileapex.domain.clipboard.ClipboardSendRequest? = null
    ): Boolean {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return false
        if (!FcmWakeBackend.isConfigured()) return false
        val selfId = loadLocalIdentity().deviceId
        return runCatching {
            val targets = GoogleLinkCoordinator.fcmTargetsForDevices(selfId, listOf(targetDeviceId))
            val target = targets.firstOrNull() ?: return false
            FcmWakeBackend.sendClipboardOptIn(
                targetFcmToken = target.fcmToken,
                sourceDeviceId = selfId,
                senderDeviceName = senderDeviceName,
                pendingPayload = pendingPayload
            )
        }.getOrElse { error ->
            println("FcmWakeCoordinator: clipboard opt-in FCM failed - ${error.message}")
            false
        }
    }

    suspend fun dispatchDriveRelayPointer(entryId: String, targetDeviceIds: List<String> = emptyList()) {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) {
            println("FcmWakeCoordinator: Drive FCM skipped - Google Account not linked")
            return
        }
        if (!FcmWakeBackend.isConfigured()) {
            println("FcmWakeCoordinator: Drive FCM skipped - Admin SDK is not configured")
            return
        }
        val selfId = loadLocalIdentity().deviceId
        runCatching {
            val targets = GoogleLinkCoordinator.fcmTargetsForDevices(selfId, targetDeviceIds)
            if (targets.isEmpty()) {
                println(
                    "FcmWakeCoordinator: Drive FCM skipped - no Android FCM token for " +
                        targetDeviceIds.joinToString().ifBlank { "linked peers" }
                )
                return
            }
            for (target in targets) {
                val sent = FcmWakeBackend.sendDriveRelayPointer(
                    targetFcmToken = target.fcmToken,
                    sourceDeviceId = selfId,
                    entryId = entryId
                )
                println(
                    "FcmWakeCoordinator: Drive FCM ${if (sent) "sent" else "failed"} " +
                        "entry=$entryId device=${target.deviceId}"
                )
            }
        }.onFailure { error ->
            println("FcmWakeCoordinator: Drive relay FCM failed - ${error.message}")
        }
    }

    fun dispatchNoteDeleteToLinkedPeers(
        noteId: String,
        driveFileId: String? = null,
        checksum: String? = null,
        attachmentName: String? = null
    ) {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!FcmWakeBackend.isConfigured()) return
        val selfId = loadLocalIdentity().deviceId
        scope.launch {
            runCatching {
                val targets = GoogleLinkCoordinator.linkedPeerFcmTargets(selfId)
                for (target in targets) {
                    FcmWakeBackend.sendNoteDeleteWake(
                        targetFcmToken = target.fcmToken,
                        sourceDeviceId = selfId,
                        noteId = noteId,
                        driveFileId = driveFileId,
                        checksum = checksum,
                        attachmentName = attachmentName
                    )
                }
            }.onFailure { error ->
                println("FcmWakeCoordinator: Note delete FCM wake dispatch failed - ${error.message}")
            }
        }
    }
}

data class FcmWakeTarget(
    val deviceId: String,
    val fcmToken: String
)
