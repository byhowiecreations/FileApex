package com.fileapex.presentation

import com.fileapex.domain.presence.RouteKind
import com.fileapex.i18n.AppI18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceListRowPickerStatusTest {
    private fun status(online: Boolean, via: RouteKind?, path: String? = null) =
        DeviceListRow.pickerStatus(online, via, path, "10.0.0.208")

    @Test
    fun anOfflineDeviceSaysOfflineBecauseNothingCanBeTappedWhilePicking() {
        assertEquals(AppI18n.t("offline"), status(false, RouteKind.Lan))
        assertNotEquals(AppI18n.t("tap_to_wake"), status(false, null))
    }

    @Test
    fun aLanDeviceShowsItsAddressWithoutAPort() {
        val text = status(true, RouteKind.Lan)
        assertEquals(AppI18n.t("reach_lan_ip", "10.0.0.208"), text)
        assertNotEquals("reach_lan_ip", text)
        assertEquals(false, text.contains(":8080"))
    }

    @Test
    fun irohShowsWhetherTheConnectionIsDirectRelayedOrOnTheCustomRelay() {
        assertEquals(AppI18n.t("reach_iroh_direct_short"), status(true, RouteKind.Iroh, "direct"))
        assertEquals(AppI18n.t("reach_iroh_relay_short"), status(true, RouteKind.Iroh, "relay"))
        assertEquals(AppI18n.t("reach_iroh_custom_relay"), status(true, RouteKind.Iroh, "custom_relay"))
        assertEquals(AppI18n.t("reach_iroh"), status(true, RouteKind.Iroh, null))
    }

    @Test
    fun theLabelsReallyExistAndAreDistinct() {
        val keys = listOf("reach_lan_ip", "reach_iroh_direct_short", "reach_iroh_relay_short", "reach_iroh_custom_relay", "reached_iroh_custom_relay")
        keys.forEach { assertNotEquals("missing string $it", it, AppI18n.t(it, "x")) }
        val texts = keys.map { AppI18n.t(it, "x") }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun tailscaleAndUnknownRoutesKeepTheirShortNames() {
        assertEquals(AppI18n.t("reach_tailscale"), status(true, RouteKind.Tailnet))
        assertEquals(AppI18n.t("ready"), status(true, null))
    }

    @Test
    fun theDetailRowNamesTheCustomRelayInsteadOfCallingItDirect() {
        assertEquals(AppI18n.t("reached_iroh_custom_relay"), DeviceListRow.reachedByDetail(RouteKind.Iroh, "custom_relay"))
        assertEquals(AppI18n.t("reached_iroh_relay"), DeviceListRow.reachedByDetail(RouteKind.Iroh, "relay"))
        assertEquals(AppI18n.t("reached_iroh_direct"), DeviceListRow.reachedByDetail(RouteKind.Iroh, "direct"))
    }
}
