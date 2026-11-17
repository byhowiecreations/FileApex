package com.fileapex.structure

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The goodbye and the wake label are wired where a quit or a card needs them; a later edit cannot quietly drop them. */
class PresenceWiringGuardTest {
    private fun repoFile(path: String): File = File(path).takeIf { it.exists() } ?: File("..").resolve(path)

    @Test
    fun theDesktopQuitSaysGoodbyeBeforeTheServerStops() {
        val text = repoFile("composeApp/src/desktopMain/kotlin/com/fileapex/Main.kt").readText()
        val goodbye = text.indexOf("sayGoodbyeToOnlinePeers")
        val stop = text.indexOf("DesktopShareServerController.shutdownForQuit()")
        assertTrue("desktop quit does not say goodbye", goodbye >= 0)
        assertTrue("goodbye must come before the server stops", goodbye < stop)
    }

    @Test
    fun theAndroidExitSaysGoodbyeBeforeTheServerStops() {
        val text = repoFile("composeApp/src/androidMain/kotlin/com/fileapex/MainActivity.kt").readText()
        val exit = text.substringAfter("private fun exitFileApex()").substringBefore("private fun configureVisibleSystemBars")
        val goodbye = exit.indexOf("sayGoodbyeToOnlinePeers")
        val stop = exit.indexOf("stopShareServer()")
        assertTrue("android exit does not say goodbye", goodbye >= 0)
        assertTrue("goodbye must come before the server stops", goodbye < stop)
    }

    @Test
    fun onlyDeviceListRowChoosesTheOfflineWording() {
        val offenders = repoFile("shared/src/commonMain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "DeviceListRow.kt" }
            .filter { it.readText().contains("\"tap_to_wake\"") }
            .map { it.name }.toList()
        assertEquals("these print Tap to wake without asking offlineLabel: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun theDeviceRowsAreGivenTheirWakeSupport() {
        val text = repoFile("shared/src/commonMain/kotlin/com/fileapex/presentation/DevicesViewModel.kt").readText()
        assertTrue(text.contains("PeerWakeSupport.canWake"))
        assertTrue("an F-Droid build must be passed in", text.contains("thisBuildIsFdroid = FileApexServices.isFdroidBuild"))
    }
}
