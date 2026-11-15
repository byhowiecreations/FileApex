package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.theme.FileApexTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleConnectedOverviewDesktopRenderTest {
    private val events = mutableListOf<String>()

    private fun overview(widthDp: Int, heightPx: Int = 1100, os: String = "Android"): ImageComposeScene {
        val row = DeviceListRow(deviceId = "peer-1", deviceName = "Samsung Fold8", online = true, appVersion = "0.21.1", os = os)
        return ImageComposeScene(width = widthDp, height = heightPx, density = Density(1f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                SimpleConnectedOverview(
                    active = row,
                    snapshot = null,
                    reachedVia = null,
                    irohPath = null,
                    isRefreshingStatus = false,
                    dropHover = false,
                    clipboardSharingEnabled = true,
                    clipboardPromptHint = null,
                    onRefreshStatus = {},
                    onChooseFilesToSend = { events += "choose" },
                    onBrowseDevice = { events += "browse" },
                    onOpenSplitView = { events += "split" },
                    onShareAsLink = { events += "share" },
                    onClipboardAction = { events += "clipboard" },
                    onLocatePhone = { events += "locate" },
                    onOpenDevices = { events += "devices" },
                    notificationContent = {},
                    onOpenDeviceDetails = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun click(scene: ImageComposeScene, x: Float, y: Float, time: Long) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), timeMillis = time)
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Mouse, timeMillis = time + 10)
    }

    @Test
    fun aWideDesktopWindowPutsShareBesideTheDropAreaAndDevicesNextToBrowse() {
        val scene = overview(960)
        try {
            val image = scene.render(0L)
            File("build/tmp/overview-desktop-960dp.png").also { it.parentFile.mkdirs() }.writeBytes(image.encodeToData()!!.bytes)
            var time = 100L
            for (y in 120 until 1000 step 24) {
                click(scene, 960f * 0.75f, y.toFloat(), time)
                time += 100
                scene.render(time * 1_000_000L)
            }
            val firstHits = events.fold(mutableListOf<String>()) { acc, e -> if (acc.lastOrNull() != e) acc += e; acc }
            assertEquals(listOf("share", "devices"), firstHits.filter { it == "share" || it == "devices" })
            assertEquals("the right half of the first row is Share, not Choose Files", "share", firstHits.first { it != "choose" })
        } finally {
            scene.close()
        }
    }

    @Test
    fun theDefaultDesktopWindowWidthAlreadySplitsTheDropRow() {
        val scene = overview(480)
        try {
            val image = scene.render(0L)
            File("build/tmp/overview-desktop-480dp.png").also { it.parentFile.mkdirs() }.writeBytes(image.encodeToData()!!.bytes)
            var time = 100L
            for (y in 120 until 1000 step 24) {
                click(scene, 480f * 0.75f, y.toFloat(), time)
                time += 100
                scene.render(time * 1_000_000L)
            }
            val order = events.fold(mutableListOf<String>()) { acc, e -> if (acc.lastOrNull() != e) acc += e; acc }
            assertEquals(listOf("share", "devices"), order.filter { it == "share" || it == "devices" })
        } finally {
            scene.close()
        }
    }

    @Test
    fun theDropCardIsAStandardLeftAlignedCardNotATallCenteredZone() {
        val scene = overview(480)
        try {
            val image = scene.render(0L)
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
            // The Choose Files pill is the mint-filled area in the left two thirds of the top row.
            var pillMinX = Int.MAX_VALUE
            for (y in 60 until 240) for (x in 0 until 300) {
                val c = bitmap.getColor(x, y)
                val r = org.jetbrains.skia.Color.getR(c); val g = org.jetbrains.skia.Color.getG(c); val b = org.jetbrains.skia.Color.getB(c)
                if (r in 30..80 && g in 200..240 && b in 180..220) pillMinX = minOf(pillMinX, x)
            }
            assertTrue("the Choose Files pill starts at x=$pillMinX; it belongs at the card's left padding like the other cards' pills", pillMinX in 0..40)
            click(scene, 60f, 250f, 100L)
            scene.render(200_000_000L)
            assertEquals("the cards start right under a compact drop row, so a tap at y=250 lands on the Browse card", listOf("split"), events.take(1))
        } finally {
            scene.close()
        }
    }

    @Test
    fun aNarrowDesktopWindowKeepsShareInTheCardGrid() {
        val scene = overview(400)
        try {
            val image = scene.render(0L)
            File("build/tmp/overview-desktop-400dp.png").also { it.parentFile.mkdirs() }.writeBytes(image.encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }
}
