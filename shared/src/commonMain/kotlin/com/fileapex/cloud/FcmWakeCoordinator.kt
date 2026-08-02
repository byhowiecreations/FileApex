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
                println("FcmWakeCoordinator: FCM wake dispatch failed — ${error.message}")
            }
        }
    }

    /** Called from FCM receiver after parsing [FcmWakeProtocol]. */
    fun isPresenceWake(type: String?): Boolean = type == FcmWakeProtocol.TYPE_PRESENCE_WAKE
}

data class FcmWakeTarget(
    val deviceId: String,
    val fcmToken: String
)
