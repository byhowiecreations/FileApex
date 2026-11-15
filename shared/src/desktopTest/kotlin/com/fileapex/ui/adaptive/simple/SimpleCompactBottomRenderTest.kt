package com.fileapex.ui.adaptive.simple

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.ui.adaptive.SimpleDestination
import com.fileapex.ui.theme.FileApexTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleCompactBottomRenderTest {
    private fun tapAt(widthDp: Int, tabIndexOfFour: Int, selected: MutableList<SimpleDestination>, notes: IntArray) {
        val scene = ImageComposeScene(width = widthDp * 2, height = 200, density = Density(2f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                SimpleCompactBottom(
                    destination = SimpleDestination.LocalFiles,
                    bulletinUnreadCount = 0,
                    onSelect = { selected += it },
                    onOpenNotes = { notes[0]++ }
                )
            }
        }
        try {
            val image = scene.render(0L)
            File("build/tmp/simple-bar-${widthDp}dp.png").also { it.parentFile.mkdirs() }
                .writeBytes(image.encodeToData()!!.bytes)
            val barPx = widthDp * 2f
            val x = (barPx - 16f) / 4f * (tabIndexOfFour + 0.5f) + 8f
            val y = 200f - 70f
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), timeMillis = 10L)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Mouse, timeMillis = 20L)
            scene.render(30_000_000L)
        } finally {
            scene.close()
        }
    }

    @Test
    fun theThirdTabOpensTheBulletinBoardAtPhoneWidth() {
        val selected = mutableListOf<SimpleDestination>()
        val notes = IntArray(1)
        tapAt(360, 2, selected, notes)
        assertEquals(1, notes[0])
        assertTrue(selected.isEmpty())
    }

    @Test
    fun theSecondTabOpensRemoteFilesAndTheFourthSettingsOnANarrowScreen() {
        val selected = mutableListOf<SimpleDestination>()
        val notes = IntArray(1)
        tapAt(320, 1, selected, notes)
        tapAt(320, 3, selected, notes)
        assertEquals(listOf(SimpleDestination.Browse, SimpleDestination.Settings), selected)
        assertEquals(0, notes[0])
    }
}
