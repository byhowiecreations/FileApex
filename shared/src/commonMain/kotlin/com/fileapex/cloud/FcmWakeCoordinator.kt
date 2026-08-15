package com.fileapex.cloud

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
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

    fun dispatchPresenceWakeToLinkedPeers() {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!FcmWakeBackend.isConfigured()) return
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

    fun isNoteDelete(type: String?): Boolean = type == FcmWakeProtocol.TYPE_NOTE_DELETE

    fun isDriveRelay(type: String?): Boolean = type == FcmWakeProtocol.TYPE_DRIVE_RELAY

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

    fun dispatchNoteDeleteToLinkedPeers(noteId: String) {
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
                        noteId = noteId
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
