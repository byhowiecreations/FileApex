package com.fileapex.domain.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCopySignalsTest {

    @Test
    fun recognizesCopyChromeInEnglishSpanishAndChinese() {
        assertTrue(ClipboardCopySignals.isCopyLabel("Copy"))
        assertTrue(ClipboardCopySignals.isCopyLabel("Copy link"))
        assertTrue(ClipboardCopySignals.isCopyLabel("Copiar"))
        assertTrue(ClipboardCopySignals.isCopyLabel("Copiar enlace"))
        assertTrue(ClipboardCopySignals.isCopyLabel("复制"))
        assertTrue(ClipboardCopySignals.isCopyLabel("复制链接"))
        assertTrue(ClipboardCopySignals.isCopyLabel("剪切"))
        assertFalse(ClipboardCopySignals.isCopyLabel("copyright"))
        assertFalse(ClipboardCopySignals.isCopyLabel("Share"))
    }

    @Test
    fun ignoresCopiedToastsAsPayload() {
        assertTrue(ClipboardCopySignals.isCopyStatusOnly("Copied to clipboard"))
        assertTrue(ClipboardCopySignals.isCopyStatusOnly("Copiado al portapapeles"))
        assertTrue(ClipboardCopySignals.isCopyStatusOnly("已复制到剪贴板"))
        assertFalse(ClipboardCopySignals.isUsablePayload("Copied"))
        assertTrue(ClipboardCopySignals.isUsablePayload("copy the deployment notes"))
        assertTrue(ClipboardCopySignals.isUsablePayload("https://example.com"))
    }

    @Test
    fun keepsFullFieldSelectionInsteadOfDroppingIt() {
        val full = "entire clipboard payload"
        assertEquals(full, ClipboardCopySignals.selectedSlice(full, 0, full.length))
        assertEquals("clip", ClipboardCopySignals.selectedSlice("xxclipyy", 2, 6))
        assertEquals("", ClipboardCopySignals.selectedSlice(full, -1, 4))
    }

    @Test
    fun copyViewIdsMatchOemMenus() {
        assertTrue(ClipboardCopySignals.isCopyViewId("com.android.chrome:id/copy"))
        assertTrue(ClipboardCopySignals.isCopyViewId("app:id/action_copy"))
        assertFalse(ClipboardCopySignals.isCopyViewId("app:id/copyright"))
        assertFalse(ClipboardCopySignals.isCopyViewId(null))
    }

    @Test
    fun miuiTaplusOverlayCountsAsCopy() {
        assertTrue(
            ClipboardCopySignals.isOemClipboardOverlay(
                "com.miui.contentextension",
                "com.miui.contentextension.text.floatview.TaplusSplashFloatView"
            )
        )
        assertTrue(
            ClipboardCopySignals.isOemClipboardOverlay(
                "com.miui.contentcatcher",
                "com.miui.contentcatcher.clipboard.ClipboardCatcher"
            )
        )
        assertFalse(
            ClipboardCopySignals.isOemClipboardOverlay(
                "com.miui.contentextension",
                "com.miui.contentextension.settings.SettingsActivity"
            )
        )
        assertFalse(
            ClipboardCopySignals.isOemClipboardOverlay(
                "com.sohu.inputmethod.sogou.xiaomi",
                "ClipboardFirstCandidateView"
            )
        )
        assertFalse(ClipboardCopySignals.isOemClipboardOverlay(null, null))
    }

    @Test
    fun nodeEventPrefersSourceTextAndSelection() {
        assertEquals(
            "selected",
            ClipboardCopySignals.textFromNodeEvent(
                eventTexts = listOf("ignored field"),
                sourceText = "xxselectedyy",
                fromIndex = -1,
                toIndex = -1,
                selectionStart = 2,
                selectionEnd = 10
            )
        )
        assertEquals(
            "from node",
            ClipboardCopySignals.textFromNodeEvent(
                eventTexts = listOf("fallback"),
                sourceText = "from node",
                fromIndex = -1,
                toIndex = -1
            )
        )
        assertEquals(
            "event",
            ClipboardCopySignals.textFromNodeEvent(
                eventTexts = listOf("event"),
                sourceText = null,
                fromIndex = -1,
                toIndex = -1
            )
        )
        assertTrue(ClipboardCopySignals.preferCachedNodeOverClipboard(windowFocused = false))
        assertFalse(ClipboardCopySignals.preferCachedNodeOverClipboard(windowFocused = true))
    }

    @Test
    fun prepareRejectsOversizedAndBinaryPayloads() {
        assertTrue(ClipboardCopySignals.prepare("hello") is ClipboardCopySignals.Prepared.Ok)
        assertTrue(ClipboardCopySignals.prepare("   ") is ClipboardCopySignals.Prepared.Empty)
        assertTrue(
            ClipboardCopySignals.prepare("x".repeat(ClipboardCopySignals.MAX_PAYLOAD_CHARS + 1))
                is ClipboardCopySignals.Prepared.TooLarge
        )
        assertTrue(
            ClipboardCopySignals.prepare("ok\u0000payload") is ClipboardCopySignals.Prepared.NotText
        )
        assertFalse(ClipboardCopySignals.clipHasShareableText(listOf("image/png")))
        assertTrue(ClipboardCopySignals.clipHasShareableText(listOf("text/plain", "image/png")))
        assertEquals(
            ClipboardCopySignals.MAX_PAYLOAD_CHARS + 1,
            ClipboardCopySignals.boundedRaw("z".repeat(ClipboardCopySignals.MAX_PAYLOAD_CHARS + 50))?.length
        )
    }
}
