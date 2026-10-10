package com.fileapex.domain.notifications

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardE2ee
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import com.fileapex.domain.presence.isTailscaleEnabled
import com.fileapex.domain.presence.resolvePeerEndpoint
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

object NotificationBroadcaster {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun targetDevice(): PairedDeviceEntity? {
        val devices = FileApexServices.deviceRepository.listDevices().filterNot { it.isRemoved }
        val chosen = FileApexServices.settings.notificationBroadcastTargetDeviceId.value
        return devices.firstOrNull { it.deviceId == chosen } ?: devices.singleOrNull()
    }

    suspend fun deliver(payload: NotificationPayload): Boolean {
        val device = targetDevice()
        if (device == null) {
            println("NotificationBroadcaster: not sent, no target device chosen or more than one paired device")
            return false
        }
        val endpoint = resolvePeerEndpoint(device, isTailscaleEnabled())
        if (endpoint == null) {
            println("NotificationBroadcaster: not sent, no endpoint for ${device.deviceId}")
            return false
        }
        val peerKey = ClipboardShareCoordinator.resolvePeerPublicKey(device)
        if (peerKey.isBlank()) {
            println("NotificationBroadcaster: not sent, no public key for ${device.deviceId}")
            return false
        }
        val identity = loadLocalIdentity()
        val ciphertext = ClipboardE2ee.encrypt(
            plaintext = json.encodeToString(NotificationPayload.serializer(), payload).encodeToByteArray(),
            localDeviceId = identity.deviceId,
            peerDeviceId = device.deviceId,
            peerPublicKeyBase64 = peerKey
        )
        val request = NotificationEventRequest(
            senderDeviceId = identity.deviceId,
            senderDeviceName = identity.deviceName,
            senderPublicKey = ClipboardE2ee.publicKeyBase64(),
            ciphertext = ciphertext,
            sentAtEpochMs = TimeUtils.now()
        )
        return cancellableCatching {
            FileApexServices.client.postNotificationEvent(endpoint.host, endpoint.port, request)
        }.onFailure { println("NotificationBroadcaster: delivery failed - ${it::class.simpleName}") }.isSuccess
    }

    /** Clears these notifications here at once, then tells the phone to dismiss them. */
    fun dismissOnDevice(deviceId: String, keys: List<String>) {
        NotificationInbox.removeKeys(keys)
        scope.launch {
            val device = FileApexServices.deviceRepository.getDevice(deviceId) ?: return@launch
            sendCommand(device, NotificationCommand(NotificationAction.DISMISS, keys))
        }
    }

    suspend fun replyOnDevice(deviceId: String, key: String, text: String): Boolean {
        val device = FileApexServices.deviceRepository.getDevice(deviceId) ?: return false
        return sendCommand(device, NotificationCommand(NotificationAction.REPLY, listOf(key), text))
    }

    /** Asks [device] to dismiss or reply to its own notifications. False when it could not be reached. */
    suspend fun sendCommand(device: PairedDeviceEntity, command: NotificationCommand): Boolean {
        val endpoint = resolvePeerEndpoint(device, isTailscaleEnabled()) ?: return false
        val peerKey = ClipboardShareCoordinator.resolvePeerPublicKey(device)
        if (peerKey.isBlank()) return false
        val identity = loadLocalIdentity()
        val ciphertext = ClipboardE2ee.encrypt(
            plaintext = json.encodeToString(NotificationCommand.serializer(), command).encodeToByteArray(),
            localDeviceId = identity.deviceId,
            peerDeviceId = device.deviceId,
            peerPublicKeyBase64 = peerKey
        )
        val request = NotificationCommandRequest(
            senderDeviceId = identity.deviceId,
            senderPublicKey = ClipboardE2ee.publicKeyBase64(),
            ciphertext = ciphertext,
            sentAtEpochMs = TimeUtils.now()
        )
        return cancellableCatching {
            FileApexServices.client.postNotificationCommand(endpoint.host, endpoint.port, request)
        }.onFailure { println("NotificationBroadcaster: command failed - ${it::class.simpleName}") }.isSuccess
    }

    suspend fun requestPhonePrompt(device: PairedDeviceEntity, page: String): Boolean {
        val endpoint = resolvePeerEndpoint(device, isTailscaleEnabled()) ?: return false
        val identity = loadLocalIdentity()
        val request = PhoneSettingsPromptRequest(
            senderDeviceId = identity.deviceId,
            senderDeviceName = identity.deviceName,
            page = page
        )
        return cancellableCatching {
            FileApexServices.client.postPhoneSettingsPrompt(endpoint.host, endpoint.port, request)
        }.onFailure { println("NotificationBroadcaster: prompt failed - ${it::class.simpleName}") }.isSuccess
    }

    /** Null when the device is unreachable or too old to know about notification sync. */
    suspend fun fetchStatus(device: PairedDeviceEntity): NotificationSyncStatus? =
        (checkPhone(device) as? PhoneNotificationState.Known)?.status

    /**
     * One look at the phone: whether it is reachable, whether it knows notification sync, and, when
     * sync is on, what it is showing right now. [PhoneNotificationState.Unreachable] is retryable;
     * [PhoneNotificationState.NeedsUpdate] is not.
     */
    suspend fun checkPhone(device: PairedDeviceEntity): PhoneNotificationState {
        val endpoint = resolvePeerEndpoint(device, isTailscaleEnabled()) ?: return PhoneNotificationState.Unreachable
        val status = try {
            FileApexServices.client.getNotificationSyncStatus(endpoint.host, endpoint.port)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Network boundary: a failed call here means unreachable, not unsupported.
            println("NotificationBroadcaster: status failed - ${error::class.simpleName}")
            return PhoneNotificationState.Unreachable
        } ?: return PhoneNotificationState.NeedsUpdate
        if (!status.supported) return PhoneNotificationState.NeedsUpdate
        if (status.enabled && status.accessGranted) fetchSnapshot(device, endpoint.host, endpoint.port)
        return PhoneNotificationState.Known(status)
    }

    private suspend fun fetchSnapshot(device: PairedDeviceEntity, host: String, port: Int) {
        val peerKey = ClipboardShareCoordinator.resolvePeerPublicKey(device)
        if (peerKey.isBlank()) return
        val identity = loadLocalIdentity()
        cancellableCatching {
            val response = FileApexServices.client.postNotificationSnapshot(
                host,
                port,
                NotificationSnapshotRequest(identity.deviceId, ClipboardE2ee.publicKeyBase64())
            )
            val plaintext = ClipboardE2ee.decrypt(
                ciphertextBase64 = response.ciphertext,
                localDeviceId = identity.deviceId,
                peerDeviceId = device.deviceId,
                peerPublicKeyBase64 = peerKey
            ).decodeToString()
            val snapshot = json.decodeFromString(NotificationSnapshot.serializer(), plaintext)
            NotificationInbox.replaceAll(snapshot.items.map { it.sanitized() })
        }.onFailure { println("NotificationBroadcaster: snapshot failed - ${it::class.simpleName}") }
    }
}

sealed interface PhoneNotificationState {
    data object Unreachable : PhoneNotificationState
    data object NeedsUpdate : PhoneNotificationState
    data class Known(val status: NotificationSyncStatus) : PhoneNotificationState
}
