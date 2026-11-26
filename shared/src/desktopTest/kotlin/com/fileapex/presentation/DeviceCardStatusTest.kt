package com.fileapex.presentation

import com.fileapex.domain.presence.RouteKind
import com.fileapex.i18n.AppI18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device cards show a route traffic light and a version only for a device that is behind the newest in the cluster. */
class DeviceCardStatusTest {
    private fun row(
        id: String,
        code: Int,
        version: String? = "0.21.$code",
        online: Boolean = true,
        reachedBy: RouteKind? = RouteKind.Lan
    ) = DeviceListRow(
        deviceId = id,
        deviceName = id,
        online = online,
        appVersion = version,
        appVersionCode = code,
        reachedBy = reachedBy
    )

    @Test
    fun `lan is green, tailscale and iroh are yellow, offline is red`() {
        assertEquals(RouteLight.Green, row("a", 10, reachedBy = RouteKind.Lan).routeLight)
        assertEquals(RouteLight.Yellow, row("a", 10, reachedBy = RouteKind.Tailnet).routeLight)
        assertEquals(RouteLight.Yellow, row("a", 10, reachedBy = RouteKind.Iroh).routeLight)
        assertEquals(RouteLight.Red, row("a", 10, online = false).routeLight)
    }

    @Test
    fun `online with no route known yet is green`() {
        assertEquals(RouteLight.Green, row("a", 10, reachedBy = null).routeLight)
    }

    @Test
    fun `only devices below the newest version are marked behind`() {
        val marked = DeviceListRow.markBehind(listOf(row("a", 10), row("b", 9), row("c", 10)), ownVersionName = "0.21.10")
        assertEquals(listOf(false, true, false), marked.map { it.isBehind })
    }

    @Test
    fun `this device being newer than every peer marks the peers behind`() {
        val marked = DeviceListRow.markBehind(listOf(row("a", 9), row("b", 9)), ownVersionName = "0.21.10")
        assertTrue(marked.all { it.isBehind })
    }

    @Test
    fun `an unknown version is never behind`() {
        val marked = DeviceListRow.markBehind(listOf(row("a", 0, version = null), row("b", 10)), ownVersionName = "0.21.10")
        assertFalse(marked.first().isBehind)
    }

    @Test
    fun `versions compare by number not by text, and a missing code does not matter`() {
        val marked = DeviceListRow.markBehind(
            listOf(row("a", 0, version = "0.21.10"), row("b", 0, version = "0.21.9")),
            ownVersionName = "0.21.10"
        )
        assertEquals(listOf(false, true), marked.map { it.isBehind })
    }

    @Test
    fun `an up to date online card has no caption`() {
        assertNull(row("a", 10).cardCaption)
    }

    @Test
    fun `a behind online card shows its version`() {
        val behind = DeviceListRow.markBehind(listOf(row("a", 10), row("b", 9)), ownVersionName = "0.21.10").last()
        assertEquals("v0.21.9", behind.cardCaption)
    }

    @Test
    fun `an offline card says offline`() {
        assertEquals(AppI18n.t("offline"), row("a", 10, online = false).cardCaption)
    }
}
