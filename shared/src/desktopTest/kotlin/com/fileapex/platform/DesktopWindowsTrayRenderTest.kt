package com.fileapex.platform

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.fileapex.presentation.DeviceIconKind
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Draws the Windows tray menu with 3, 9 and 30 devices. Pictures go to the system temp folder for a look. */
class DesktopWindowsTrayRenderTest {
    private val kinds = listOf("MacDesktop", "AndroidPhone", "FoldablePhone", "Docker", "WindowsPc", "LinuxServer", "AndroidTablet")

    private fun devices(count: Int) = List(count) { i ->
        DesktopTrayDeviceSnapshot(
            id = "d$i",
            name = "Device number ${i + 1}",
            isOnline = i % 5 != 4,
            iconKind = kinds[i % kinds.size],
            batteryText = if (i % 3 == 0) "${90 - i * 3}% 14:02" else "",
            batteryTone = when {
                i % 3 != 0 -> ""
                90 - i * 3 >= 40 -> TrayBatteryTone.Good.name
                90 - i * 3 >= 20 -> TrayBatteryTone.Low.name
                else -> TrayBatteryTone.Critical.name
            }
        )
    }

    private fun render(count: Int, selected: Set<String>, name: String): Pair<Int, Int> {
        val list = devices(count)
        val columns = trayColumnCount(list.size, 900, TRAY_ROW_HEIGHT_DP, TRAY_CHROME_HEIGHT_DP)
        val width = trayMenuWidthDp(columns)
        val scene = ImageComposeScene(width = width * 2, height = 1800, density = Density(2f)) {
            DesktopWindowsTrayMenu(
                devices = list,
                selectedDeviceIds = selected,
                columns = columns,
                checkingBatteries = false,
                onDeviceClick = {},
                onDeviceCtrlClick = {},
                onSendClipboard = {},
                onBrowse = {},
                onPostNote = {},
                onCheckBatteries = {},
                onSendMulti = {},
                onLaunchApp = {},
                onQuitApp = {}
            )
        }
        val image = scene.render()
        scene.close()
        File(System.getProperty("java.io.tmpdir"), "tray-windows-$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return width to columns
    }

    @Test
    fun threeDevicesUseOneColumn() {
        val (width, columns) = render(3, emptySet(), "3")
        assertEquals(1, columns)
        assertEquals(trayMenuWidthDp(1), width)
    }

    @Test
    fun nineDevicesWithTwoSelectedStayInOneColumn() {
        val (_, columns) = render(9, setOf("d0", "d1"), "9-selected")
        assertEquals(1, columns)
    }

    @Test
    fun thirtyDevicesFlowIntoThreeColumns() {
        val (width, columns) = render(30, emptySet(), "30")
        assertEquals(3, columns)
        assertTrue(width > trayMenuWidthDp(2))
    }

    @Test
    fun everyDeviceKindHasAnIconName() {
        assertTrue(DeviceIconKind.entries.all { it.name.isNotBlank() })
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun pixelsAfterHover(at: Offset): Pair<ByteArray, ByteArray> {
        val list = devices(3)
        val scene = ImageComposeScene(width = trayMenuWidthDp(1) * 2, height = 900, density = Density(2f)) {
            DesktopWindowsTrayMenu(
                devices = list, selectedDeviceIds = emptySet(), columns = 1, checkingBatteries = false,
                onDeviceClick = {}, onDeviceCtrlClick = {}, onSendClipboard = {}, onBrowse = {}, onPostNote = {},
                onCheckBatteries = {}, onSendMulti = {}, onLaunchApp = {}, onQuitApp = {}
            )
        }
        var nanos = 0L
        fun frame() = scene.render(nanos).also { nanos += 100_000_000L }
        val before = frame().encodeToData(EncodedImageFormat.PNG)!!.bytes
        scene.sendPointerEvent(PointerEventType.Enter, at)
        scene.sendPointerEvent(PointerEventType.Move, at)
        repeat(15) { frame() }
        val after = frame().encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(System.getProperty("java.io.tmpdir"), "tray-windows-tooltip.png").writeBytes(after)
        scene.close()
        return before to after
    }

    @Test
    fun hoveringTheQuitButtonShowsATooltip() {
        // Quit sits second from the right in the header: 12dp padding, Open 48dp wide, Quit centred 24dp further left.
        val x = (trayMenuWidthDp(1) - 12 - 48 - 24) * 2f
        val (before, after) = pixelsAfterHover(Offset(x, 27 * 2f))
        assertTrue("no tooltip appeared after hovering Quit", !before.contentEquals(after))
    }
}
