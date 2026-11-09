package com.fileapex.platform

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTrayModelTest {

    private fun device(name: String, os: String = "", make: String = "", model: String = "", platform: String = "", version: String = "0.20.7") =
        PairedDeviceEntity(
            deviceId = "id-$name", deviceName = name, lastKnownIp = "192.168.1.9", port = 8080, publicKeyHash = "", rootPath = "/",
            clientVersion = version, os = os, deviceMake = make, deviceModel = model, platform = platform
        )

    private fun row(name: String, online: Boolean, id: String = "id-$name") =
        DesktopTrayDeviceSnapshot(id = id, name = name, isOnline = online)

    @Test
    fun snapshotCarriesTheDeviceTypeTheAppAlreadyResolves() {
        assertEquals("MacDesktop", trayDeviceSnapshot(device("Cliff's MacBook Pro", os = "macos", make = "Apple"), true).iconKind)
        assertEquals("WindowsPc", trayDeviceSnapshot(device("Nimo", os = "windows"), true).iconKind)
        assertEquals("Docker", trayDeviceSnapshot(device("Docker OMV", version = "docker"), true).iconKind)
        assertEquals("FoldablePhone", trayDeviceSnapshot(device("Samsung Fold8", os = "android", make = "samsung", model = "SM-F971U1"), true).iconKind)
        assertEquals("AndroidPhone", trayDeviceSnapshot(device("Phone", os = "android"), true).iconKind)
    }

    @Test
    fun snapshotCarriesTheTextKeyForTheTypeTooltip() {
        assertEquals("icon_mac", trayDeviceSnapshot(device("Cliff's MacBook Pro", os = "macos", make = "Apple"), true).iconKey)
        assertEquals("icon_docker", trayDeviceSnapshot(device("Docker OMV", version = "docker"), true).iconKey)
        assertEquals("icon_foldable_phone", trayDeviceSnapshot(device("Samsung Fold8", os = "android", make = "samsung", model = "SM-F971U1"), true).iconKey)
        assertEquals("icon_android_phone", trayDeviceSnapshot(device("Phone", os = "android"), true).iconKey)
    }

    @Test
    fun everyTooltipTextExistsInAllThreeLanguages() {
        val dir = java.io.File(System.getProperty("user.dir"), "src/desktopMain/resources/i18n")
        for (language in listOf("en", "es", "zh-rCN")) {
            val text = java.io.File(dir, "$language.xml").readText()
            for (key in TRAY_TEXT_KEYS) {
                assertTrue("$language is missing $key", text.contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun snapshotKeepsIdNameAndOnlineState() {
        val snapshot = trayDeviceSnapshot(device("Amanda", os = "android"), false)
        assertEquals("id-Amanda", snapshot.id)
        assertEquals("Amanda", snapshot.name)
        assertEquals(false, snapshot.isOnline)
    }

    @Test
    fun onlineDevicesComeFirstAndOrderInsideAGroupIsKept() {
        val ordered = orderTrayDevices(listOf(row("a", false), row("b", true), row("c", false), row("d", true)))
        assertEquals(listOf("b", "d", "a", "c"), ordered.map { it.name })
    }

    @Test
    fun friendsStayAfterEveryDevice() {
        val friend = row("Joe (Friends)", true, id = "friend:abc")
        val ordered = orderTrayDevices(listOf(friend, row("a", false), row("b", true)))
        assertEquals(listOf("b", "a", "Joe (Friends)"), ordered.map { it.name })
    }

    @Test
    fun aFewDevicesStayInOneColumn() {
        assertEquals(1, trayColumnCount(rowCount = 3, availableHeightDp = 800, rowHeightDp = 52, chromeHeightDp = 150))
        assertEquals(1, trayColumnCount(rowCount = 9, availableHeightDp = 800, rowHeightDp = 52, chromeHeightDp = 150))
        assertEquals(1, trayColumnCount(rowCount = 0, availableHeightDp = 800, rowHeightDp = 52, chromeHeightDp = 150))
    }

    @Test
    fun aTallListFlowsIntoMoreColumnsInsteadOfScrolling() {
        // 800 - 150 = 650 high, 12 rows fit per column
        assertEquals(1, trayColumnCount(rowCount = 12, availableHeightDp = 800, rowHeightDp = 52, chromeHeightDp = 150))
        assertEquals(2, trayColumnCount(rowCount = 13, availableHeightDp = 800, rowHeightDp = 52, chromeHeightDp = 150))
        assertEquals(2, trayColumnCount(rowCount = 24, availableHeightDp = 800, rowHeightDp = 52, chromeHeightDp = 150))
        assertEquals(3, trayColumnCount(rowCount = 30, availableHeightDp = 800, rowHeightDp = 52, chromeHeightDp = 150))
    }

    @Test
    fun columnsAreCappedAndASmallScreenStillGivesAtLeastOneRowPerColumn() {
        assertEquals(3, trayColumnCount(rowCount = 200, availableHeightDp = 800, rowHeightDp = 52, chromeHeightDp = 150))
        assertEquals(3, trayColumnCount(rowCount = 10, availableHeightDp = 100, rowHeightDp = 52, chromeHeightDp = 150))
        assertEquals(1, trayColumnCount(rowCount = 1, availableHeightDp = 100, rowHeightDp = 52, chromeHeightDp = 150))
    }

    @Test
    fun batteryToneFollowsTheLevel() {
        assertEquals(TrayBatteryTone.Good, trayBatteryTone(100))
        assertEquals(TrayBatteryTone.Good, trayBatteryTone(40))
        assertEquals(TrayBatteryTone.Low, trayBatteryTone(39))
        assertEquals(TrayBatteryTone.Low, trayBatteryTone(20))
        assertEquals(TrayBatteryTone.Critical, trayBatteryTone(19))
        assertEquals(TrayBatteryTone.Critical, trayBatteryTone(0))
    }

    @Test
    fun batteryResultTextShowsLevelAndTime() {
        assertEquals("82% 14:02", trayBatteryText(82, 14, 2))
        assertEquals("5% 09:07", trayBatteryText(5, 9, 7))
    }

    @Test
    fun theSnapshotJsonKeepsTheOldKeysAndAddsTheKind() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val text = json.encodeToString(DesktopTrayDeviceSnapshot.serializer(), row("a", true))
        assertTrue(text, text.contains("\"id\":\"id-a\""))
        assertTrue(text, text.contains("\"isOnline\":true"))
        assertTrue(text, text.contains("\"iconKind\":"))
    }

    private val noon = { LocalTime.of(14, 2) }

    @Test
    fun batteryCheckAsksOnlyOnlineDevicesAndSkipsFriends() = runBlocking {
        val asked = mutableListOf<String>()
        val readings = readTrayBatteries(
            devices = listOf(row("a", true), row("b", false), row("Joe", true, id = "friend:x")),
            fetch = { id -> asked += id; BatteryDiagnostics(levelPercent = 82) },
            now = noon
        )
        assertEquals(listOf("id-a"), asked)
        assertEquals(setOf("id-a"), readings.keys)
    }

    @Test
    fun batteryReadingCarriesTextAndTone() = runBlocking {
        val readings = readTrayBatteries(
            devices = listOf(row("a", true), row("b", true)),
            fetch = { id -> BatteryDiagnostics(levelPercent = if (id == "id-a") 82 else 12) },
            now = noon
        )
        assertEquals(TrayBatteryReading("82% 14:02", TrayBatteryTone.Good), readings.getValue("id-a"))
        assertEquals(TrayBatteryReading("12% 14:02", TrayBatteryTone.Critical), readings.getValue("id-b"))
    }

    @Test
    fun aDeviceThatDoesNotAnswerOrHasNoLevelGetsNoReading() = runBlocking {
        val readings = readTrayBatteries(
            devices = listOf(row("a", true), row("b", true), row("c", true)),
            fetch = { id ->
                when (id) {
                    "id-a" -> null
                    "id-b" -> BatteryDiagnostics(levelPercent = null, chargingState = "Not available")
                    else -> BatteryDiagnostics(levelPercent = 55)
                }
            },
            now = noon
        )
        assertEquals(setOf("id-c"), readings.keys)
    }

    @Test
    fun readingsAreMergedIntoTheRowsForTheViews() {
        val merged = withTrayBatteries(
            listOf(row("a", true), row("b", true)),
            mapOf("id-a" to TrayBatteryReading("82% 14:02", TrayBatteryTone.Good))
        )
        assertEquals("82% 14:02", merged[0].batteryText)
        assertEquals("Good", merged[0].batteryTone)
        assertEquals("", merged[1].batteryText)
        assertEquals("", merged[1].batteryTone)
    }
}
