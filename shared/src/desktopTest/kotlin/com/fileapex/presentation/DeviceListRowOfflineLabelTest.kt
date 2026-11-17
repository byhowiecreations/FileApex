package com.fileapex.presentation

import com.fileapex.i18n.AppI18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceListRowOfflineLabelTest {
    @Test
    fun aWakeableOfflineDeviceSaysTapToWake() {
        assertEquals(AppI18n.t("tap_to_wake"), DeviceListRow.offlineLabel(canWake = true))
    }

    @Test
    fun anOfflineDeviceThatCannotBeWokenSaysOffline() {
        assertEquals(AppI18n.t("offline"), DeviceListRow.offlineLabel(canWake = false))
    }

    @Test
    fun theSubtitleOfAnOfflineComputerStartsWithOffline() {
        val subtitle = DeviceListRow.peerStatusSubtitle(online = false, appVersion = "0.21.3", canWake = false)
        assertTrue(subtitle, subtitle.startsWith(AppI18n.t("offline")))
        assertFalse(subtitle, subtitle.contains(AppI18n.t("tap_to_wake")))
    }

    @Test
    fun theSubtitleOfAnOfflineWakeablePhoneStillSaysTapToWake() {
        val subtitle = DeviceListRow.peerStatusSubtitle(online = false, appVersion = "0.21.3", canWake = true)
        assertTrue(subtitle, subtitle.startsWith(AppI18n.t("tap_to_wake")))
    }

    @Test
    fun anOnlineDeviceIsNotAffectedByTheWakeFlag() {
        val a = DeviceListRow.peerStatusSubtitle(online = true, appVersion = "0.21.3", canWake = true)
        val b = DeviceListRow.peerStatusSubtitle(online = true, appVersion = "0.21.3", canWake = false)
        assertEquals(a, b)
    }

    @Test
    fun aRowCarriesTheWakeFlagAndDefaultsToNoWake() {
        val row = DeviceListRow(deviceId = "A", deviceName = "A", online = false, appVersion = null)
        assertFalse(row.canWake)
        assertEquals(AppI18n.t("offline"), DeviceListRow.offlineLabel(row.canWake))
    }
}
