package com.fileapex.ui.adaptive.simple

import com.fileapex.presentation.DeviceListRow
import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleRemoteFilesPolicyTest {
    private fun row(name: String, online: Boolean) =
        DeviceListRow(deviceId = name, deviceName = name, online = online, appVersion = null, os = "macos")

    @Test
    fun anOfflineConnectedDeviceIsShownAsOfflineAndNeverSwappedForAnother() {
        assertEquals(
            SimpleRemoteFilesState.Offline("MacBook Pro", canSwitch = true),
            SimpleRemoteFilesPolicy.of(row("MacBook Pro", online = false), pairedCount = 3, connecting = false)
        )
    }

    @Test
    fun withOnlyOnePairedDeviceThereIsNothingToSwitchTo() {
        assertEquals(
            SimpleRemoteFilesState.Offline("MacBook Pro", canSwitch = false),
            SimpleRemoteFilesPolicy.of(row("MacBook Pro", online = false), pairedCount = 1, connecting = false)
        )
    }

    @Test
    fun anOnlineDeviceThatDidNotOpenCanBeRetried() {
        assertEquals(
            SimpleRemoteFilesState.Retry("MacBook Pro"),
            SimpleRemoteFilesPolicy.of(row("MacBook Pro", online = true), pairedCount = 2, connecting = false)
        )
    }

    @Test
    fun whileOpeningTheDeviceTheStateIsConnecting() {
        assertEquals(SimpleRemoteFilesState.Connecting, SimpleRemoteFilesPolicy.of(row("MacBook Pro", online = true), pairedCount = 2, connecting = true))
        assertEquals(SimpleRemoteFilesState.Connecting, SimpleRemoteFilesPolicy.of(row("MacBook Pro", online = false), pairedCount = 2, connecting = true))
    }

    @Test
    fun withNoPairedDeviceTheUserIsAskedToAddOne() {
        assertEquals(SimpleRemoteFilesState.AddDevice, SimpleRemoteFilesPolicy.of(null, pairedCount = 0, connecting = false))
    }
}
