package com.fileapex.cloud

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

actual object FcmTokenRegistrar {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    actual fun start() {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        scope.launch { publishCurrentToken() }
    }

    actual fun stop() = Unit

    fun onTokenRefreshed(token: String) {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        scope.launch { publishToken(token) }
    }

    private suspend fun publishCurrentToken() {
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNotBlank()) publishToken(token)
        }.onFailure { error ->
            Log.e(TAG, "FCM token fetch failed — ${error.message}")
        }
    }

    private suspend fun publishToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        repeat(12) { attempt ->
            if (GoogleLinkCoordinator.patchSelfFcmToken(trimmed)) {
                Log.i(TAG, "FCM token published to cloud registry")
                return
            }
            delay(500L * (attempt + 1))
        }
        Log.e(TAG, "FCM token was not published — cloud session not ready")
    }

    private const val TAG = "FcmTokenRegistrar"
}
