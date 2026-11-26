package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether this device accepts custom device icons pushed by paired devices. */
class DeviceIconSyncSettings(private val store: SettingsKvStore) {
    private val acceptFromPeersFlow = MutableStateFlow(store.getBoolean(KEY_ACCEPT_FROM_PEERS, true))
    val acceptFromPeers: StateFlow<Boolean> = acceptFromPeersFlow.asStateFlow()

    fun setAcceptFromPeers(enabled: Boolean) {
        store.putBoolean(KEY_ACCEPT_FROM_PEERS, enabled)
        acceptFromPeersFlow.value = enabled
    }

    private companion object {
        const val KEY_ACCEPT_FROM_PEERS = "accept_synced_device_icons"
    }
}
