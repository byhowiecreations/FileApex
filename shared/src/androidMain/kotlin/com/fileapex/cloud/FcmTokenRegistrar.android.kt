package com.fileapex.cloud

import com.google.firebase.messaging.FirebaseMessaging
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

actual object FcmTokenRegistrar {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    actual fun start() {
        if (started) return
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        started = true
        scope.launch {
            runCatching {
                val token = FirebaseMessaging.getInstance().token.await()
                if (token.isNotBlank()) {
                    GoogleLinkCoordinator.patchSelfFcmToken(token)
                }
            }.onFailure { error ->
                println("FcmTokenRegistrar: initial token fetch failed — ${error.message}")
            }
        }
    }

    actual fun stop() {
        started = false
    }

    fun onTokenRefreshed(token: String) {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        scope.launch {
            runCatching {
                GoogleLinkCoordinator.patchSelfFcmToken(token)
            }.onFailure { error ->
                println("FcmTokenRegistrar: token refresh patch failed — ${error.message}")
            }
        }
    }
}
