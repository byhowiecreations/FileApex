package com.fileapex.data.settings

import com.fileapex.domain.clipboard.ClipboardSharePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which events raise a notification here, and what this device shares of its own notifications with another one. */
class NotificationSettingsStore(private val store: SettingsKvStore) {
    private val transfer = BooleanSetting(store, BaseAppSettings.KEY_TRANSFER_NOTIFICATIONS)
    private val driveRelay = BooleanSetting(store, BaseAppSettings.KEY_DRIVE_RELAY_NOTIFICATIONS)
    private val notes = BooleanSetting(store, BaseAppSettings.KEY_NOTES_NOTIFICATIONS, default = true)
    private val notesPromptShown = BooleanSetting(store, BaseAppSettings.KEY_NOTES_NOTIFICATION_PROMPT_SHOWN)
    private val broadcast = BooleanSetting(store, BaseAppSettings.KEY_NOTIFICATION_BROADCAST_ENABLED)
    private val codes = BooleanSetting(store, BaseAppSettings.KEY_NOTIFICATION_BROADCAST_CODES)
    private val dismissal = BooleanSetting(store, BaseAppSettings.KEY_NOTIFICATION_BROADCAST_DISMISSAL)
    private val popups = BooleanSetting(store, BaseAppSettings.KEY_DEVICE_NOTIFICATION_POPUPS)
    private val targetFlow = MutableStateFlow(store.getString(BaseAppSettings.KEY_NOTIFICATION_BROADCAST_TARGET, ""))
    private val appsFlow = MutableStateFlow(
        ClipboardSharePolicy.parseDeviceIdSet(store.getString(BaseAppSettings.KEY_NOTIFICATION_BROADCAST_APPS, ""))
    )

    // Anyone who turned sharing on before the disclosure existed already opted in, so they are not asked again.
    private val disclosure = BooleanSetting(store, BaseAppSettings.KEY_NOTIFICATION_DISCLOSURE_ACCEPTED).also {
        if (broadcast.flow.value && !it.flow.value) it.set(true)
    }

    val fileTransferEnabled: StateFlow<Boolean> = transfer.flow
    val driveRelayEnabled: StateFlow<Boolean> = driveRelay.flow
    val notesEnabled: StateFlow<Boolean> = notes.flow
    val notesPromptShownFlow: StateFlow<Boolean> = notesPromptShown.flow
    val broadcastEnabled: StateFlow<Boolean> = broadcast.flow
    val broadcastVerificationCodes: StateFlow<Boolean> = codes.flow
    val broadcastSyncDismissal: StateFlow<Boolean> = dismissal.flow
    val devicePopups: StateFlow<Boolean> = popups.flow
    val disclosureAccepted: StateFlow<Boolean> = disclosure.flow
    val broadcastTargetDeviceId: StateFlow<String> = targetFlow.asStateFlow()
    val broadcastApps: StateFlow<Set<String>> = appsFlow.asStateFlow()

    fun setFileTransferEnabled(value: Boolean) = transfer.set(value)
    fun setDriveRelayEnabled(value: Boolean) = driveRelay.set(value)
    fun setNotesEnabled(value: Boolean) = notes.set(value)
    fun setNotesPromptShown(value: Boolean) = notesPromptShown.set(value)
    fun setBroadcastEnabled(value: Boolean) = broadcast.set(value)
    fun setBroadcastVerificationCodes(value: Boolean) = codes.set(value)
    fun setBroadcastSyncDismissal(value: Boolean) = dismissal.set(value)
    fun setDevicePopups(value: Boolean) = popups.set(value)
    fun setDisclosureAccepted(value: Boolean) = disclosure.set(value)

    fun setBroadcastTargetDeviceId(deviceId: String) {
        targetFlow.value = deviceId
        store.putString(BaseAppSettings.KEY_NOTIFICATION_BROADCAST_TARGET, deviceId)
    }

    fun setBroadcastApps(packages: Set<String>) {
        val cleaned = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        appsFlow.value = cleaned
        store.putString(BaseAppSettings.KEY_NOTIFICATION_BROADCAST_APPS, ClipboardSharePolicy.encodeDeviceIdSet(cleaned))
    }

    /** The settings store has been cleared; bring the in-memory state back to the defaults. */
    fun forgetInMemory() {
        listOf(transfer, driveRelay, notes, notesPromptShown, broadcast, codes, dismissal, popups, disclosure).forEach { it.forget() }
        targetFlow.value = ""
        appsFlow.value = emptySet()
    }
}
