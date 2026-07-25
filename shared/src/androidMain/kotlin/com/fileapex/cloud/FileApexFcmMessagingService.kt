package com.fileapex.cloud

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.fileapex.domain.presence.PresenceBackgroundWake
import com.fileapex.network.ServerLifecycleManager

/**
 * Silent FCM data handler — wakes Doze'd instances for targeted peer health checks (Path A).
 * No notification channel; high-priority data-only payloads per [FcmWakeProtocol].
 */
class FileApexFcmMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (!FcmWakeCoordinator.isPresenceWake(data[FcmWakeProtocol.KEY_TYPE])) {
            return
        }
        Log.i(TAG, "Presence wake received from ${data[FcmWakeProtocol.KEY_SOURCE_DEVICE_ID]}")
        ServerLifecycleManager.ensureRunning { logMessage, error ->
            if (error != null) {
                Log.e(TAG, logMessage, error)
            } else {
                Log.i(TAG, logMessage)
            }
        }
        PresenceBackgroundWake.onRemoteWakeSignal(data[FcmWakeProtocol.KEY_SOURCE_DEVICE_ID])
    }

    override fun onNewToken(token: String) {
        FcmTokenRegistrar.onTokenRefreshed(token)
    }

    companion object {
        private const val TAG = "FileApexFcmMessaging"
    }
}
