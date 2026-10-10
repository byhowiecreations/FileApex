package com.fileapex.domain.device

import com.fileapex.di.FileApexServices
import com.fileapex.domain.notifications.NotificationCompanion
import com.fileapex.domain.notifications.NotificationCompanionGrant
import com.fileapex.domain.notifications.NotificationDebugLog
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Rings one phone until it is stopped there, stopped here, or five minutes pass. */
object PhoneLocator {
    /** The phone also stops itself after this long. */
    const val TIMEOUT_MS = 5 * 60 * 1000L

    data class Ringing(val deviceId: String, val deviceName: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ringing = MutableStateFlow<Ringing?>(null)
    private var timeout: Job? = null

    val current: StateFlow<Ringing?> = ringing.asStateFlow()

    /** Contacts only [deviceId]. True when the phone accepted the request. */
    suspend fun start(deviceId: String, deviceName: String): Boolean {
        // Only the phone this computer is listening to is ever rung.
        if (!NotificationCompanion.isCompanion(deviceId)) {
            NotificationDebugLog.log("locate refused: not the companion")
            return false
        }
        val device = FileApexServices.deviceRepository.getDevice(deviceId) ?: return false
        val endpoint = FileApexServices.presenceMonitor.resolveOutboundEndpoint(device)
        if (endpoint == null) {
            NotificationDebugLog.log("locate failed: no endpoint")
            return false
        }
        val status = cancellableCatching {
            FileApexServices.client.triggerDeviceBeepStatus(endpoint.host, endpoint.port, continuous = true)
        }.onFailure { NotificationDebugLog.log("locate failed: ${it::class.simpleName}") }.getOrNull()
        if (status == null) return false
        NotificationDebugLog.log("locate answered $status")
        if (status !in 200..299) return false
        ringing.value = Ringing(deviceId, deviceName)
        synchronized(this) {
            timeout?.cancel()
            timeout = scope.launch {
                delay(TIMEOUT_MS)
                ringing.value = null
            }
        }
        return true
    }

    /** The phone says the user stopped the alarm there. */
    fun markFound(deviceId: String) {
        if (ringing.value?.deviceId != deviceId) return
        ringing.value = null
        synchronized(this) {
            timeout?.cancel()
            timeout = null
        }
    }

    /** Phone side: tells the computer that granted this phone that the alarm was stopped here. */
    suspend fun reportFound() {
        val computerId = NotificationCompanionGrant.activeGrantor() ?: return
        val device = FileApexServices.deviceRepository.getDevice(computerId) ?: return
        val endpoint = FileApexServices.presenceMonitor.resolveOutboundEndpoint(device) ?: return
        cancellableCatching {
            FileApexServices.client.reportLocateFound(endpoint.host, endpoint.port)
        }.onFailure { println("PhoneLocator: found report failed - ${it::class.simpleName}") }
    }

    /** Clears the card at once, then tells the phone to stop. */
    fun cancel() {
        val target = ringing.value ?: return
        ringing.value = null
        synchronized(this) {
            timeout?.cancel()
            timeout = null
        }
        scope.launch {
            val device = FileApexServices.deviceRepository.getDevice(target.deviceId) ?: return@launch
            val endpoint = FileApexServices.presenceMonitor.resolveOutboundEndpoint(device) ?: return@launch
            cancellableCatching {
                FileApexServices.client.stopDeviceBeep(endpoint.host, endpoint.port)
            }.onFailure { println("PhoneLocator: stop failed - ${it::class.simpleName}") }
        }
    }
}
