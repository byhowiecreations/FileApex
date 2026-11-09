package com.fileapex.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.AppLocale
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertFalse
import org.junit.Test

/** The tray flyout and the drop box are composed once and stay open between uses, so a language change must redraw them by itself. */
class DesktopWindowsTrayLanguageTest {
    private fun drawsAgainAfterTheLanguageChanges(content: @Composable () -> Unit): Boolean {
        val before = AppI18n.locale
        val scene = ImageComposeScene(width = 320, height = 360, density = Density(1f), content = content)
        try {
            AppI18n.setLocale(AppLocale.EN)
            val english = scene.render(0L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            AppI18n.setLocale(AppLocale.ES)
            Thread.sleep(200)
            val spanish = scene.render(200_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            return !english.contentEquals(spanish)
        } finally {
            AppI18n.setLocale(before)
            scene.close()
        }
    }

    @Test
    fun theTrayMenuRedrawsInTheNewLanguage() {
        val redrawn = drawsAgainAfterTheLanguageChanges {
            DesktopWindowsTrayMenu(
                devices = emptyList(),
                selectedDeviceIds = emptySet(),
                columns = 1,
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
        assertFalse("the tray menu kept the old language after a language change", !redrawn)
    }

    @Test
    fun theDropBoxRedrawsInTheNewLanguage() {
        val redrawn = drawsAgainAfterTheLanguageChanges {
            DropBoxContent(
                stagedEntries = emptyList(),
                isSending = false,
                isPinned = false,
                isDragOver = false,
                destinationText = "Ursula-Ubuntu",
                onTogglePin = {},
                onDragOverChange = {},
                onFilesDropped = {},
                onRemovePath = {},
                onCancel = {},
                onSend = {}
            )
        }
        assertFalse("the drop box kept the old language after a language change", !redrawn)
    }
}
