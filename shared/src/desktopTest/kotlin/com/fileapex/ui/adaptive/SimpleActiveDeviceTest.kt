package com.fileapex.ui.adaptive

import com.fileapex.presentation.DeviceListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleActiveDeviceTest {

    private fun row(id: String, os: String, online: Boolean = true) =
        DeviceListRow(deviceId = id, deviceName = id, online = online, appVersion = null, os = os)

    private val phone = row("moto-signature", "android")
    private val mac = row("macbook", "macos")

    @Test
    fun aPhonePrefersTheOnlineComputerOverAnotherPhone() {
        assertEquals("macbook", pickActiveDevice(listOf(phone, mac), "", selfIsPhone = true)?.deviceId)
    }

    @Test
    fun aRealComputerBeatsAHeadlessLinuxServer() {
        val docker = row("docker", "linux")
        assertEquals("macbook", pickActiveDevice(listOf(docker, phone, mac), "", selfIsPhone = true)?.deviceId)
    }

    @Test
    fun aComputerPrefersTheOnlinePhone() {
        assertEquals("moto-signature", pickActiveDevice(listOf(mac, phone), "", selfIsPhone = false)?.deviceId)
    }

    @Test
    fun aChosenDeviceAlwaysWins() {
        assertEquals("moto-signature", pickActiveDevice(listOf(phone, mac), "moto-signature", selfIsPhone = true)?.deviceId)
    }

    @Test
    fun anOfflineComputerYieldsToAnOnlinePhone() {
        val offlineMac = row("macbook", "macos", online = false)
        assertEquals("moto-signature", pickActiveDevice(listOf(offlineMac, phone), "", selfIsPhone = true)?.deviceId)
    }

    @Test
    fun noDevicesMeansNoActiveDevice() {
        assertNull(pickActiveDevice(emptyList(), "", selfIsPhone = true))
    }

    private val amanda = row("amanda", "macos")

    @Test
    fun aPhoneFollowsTheComputerThatIsShowingItInsteadOfTheFirstOnlineComputer() {
        assertEquals("macbook", pickActiveDevice(listOf(amanda, mac, phone), "", selfIsPhone = true, followingComputerId = "macbook")?.deviceId)
    }

    @Test
    fun withoutAFollowedComputerTheFirstOnlineComputerIsStillUsed() {
        assertEquals("amanda", pickActiveDevice(listOf(amanda, mac, phone), "", selfIsPhone = true)?.deviceId)
    }

    @Test
    fun aChosenDeviceBeatsTheFollowedComputer() {
        assertEquals("amanda", pickActiveDevice(listOf(amanda, mac, phone), "amanda", selfIsPhone = true, followingComputerId = "macbook")?.deviceId)
    }

    @Test
    fun aFollowedComputerStaysEvenOfflineAndAnUnknownOneIsIgnored() {
        val offlineMac = row("macbook", "macos", online = false)
        assertEquals("macbook", pickActiveDevice(listOf(amanda, offlineMac, phone), "", selfIsPhone = true, followingComputerId = "macbook")?.deviceId)
        assertEquals("amanda", pickActiveDevice(listOf(amanda, phone), "", selfIsPhone = true, followingComputerId = "gone")?.deviceId)
    }

    @Test
    fun aComputerFollowsThePhoneThatAnnouncedItself() {
        val honor = row("honor", "android")
        assertEquals("honor", pickActiveDevice(listOf(mac, phone, honor), "", selfIsPhone = false, followingComputerId = "honor")?.deviceId)
    }

    @Test
    fun aGuessSkipsPeersThatAreLinkedElsewhere() {
        val honor = row("honor", "android")
        assertEquals("honor", pickActiveDevice(listOf(mac, phone, honor), "", selfIsPhone = false, excludedIds = setOf("moto-signature"))?.deviceId)
        assertEquals("macbook", pickActiveDevice(listOf(amanda, mac, phone), "", selfIsPhone = true, excludedIds = setOf("amanda"))?.deviceId)
    }

    @Test
    fun ifEverythingIsExcludedTheListIsNotEmptied() {
        assertEquals("moto-signature", pickActiveDevice(listOf(mac, phone), "", selfIsPhone = false, excludedIds = setOf("moto-signature"))?.deviceId)
    }
}
