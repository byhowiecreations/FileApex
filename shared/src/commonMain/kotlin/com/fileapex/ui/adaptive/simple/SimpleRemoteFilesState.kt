package com.fileapex.ui.adaptive.simple

import com.fileapex.presentation.DeviceListRow

/** What the Remote Files tab shows while no folder of the connected device is open. */
internal sealed interface SimpleRemoteFilesState {
    data object Connecting : SimpleRemoteFilesState
    data class Offline(val deviceName: String, val canSwitch: Boolean) : SimpleRemoteFilesState
    data class Retry(val deviceName: String) : SimpleRemoteFilesState
    data object AddDevice : SimpleRemoteFilesState
}

internal object SimpleRemoteFilesPolicy {
    /** The connected device stays the connected device when it is offline; the tab says so and never swaps it for another. */
    fun of(active: DeviceListRow?, pairedCount: Int, connecting: Boolean): SimpleRemoteFilesState = when {
        connecting -> SimpleRemoteFilesState.Connecting
        active == null -> SimpleRemoteFilesState.AddDevice
        !active.online -> SimpleRemoteFilesState.Offline(active.deviceName, canSwitch = pairedCount >= 2)
        else -> SimpleRemoteFilesState.Retry(active.deviceName)
    }
}
