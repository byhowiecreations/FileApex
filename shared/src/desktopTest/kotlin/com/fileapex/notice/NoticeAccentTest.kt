package com.fileapex.notice

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.fileapex.ui.notice.noticeAccentFor
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeAccentTest {
    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance()) + 0.05f
        val lo = minOf(a.luminance(), b.luminance()) + 0.05f
        return hi / lo
    }

    @Test
    fun onDarkChromeTheAccentIsLightEnoughToStandOut() {
        val accent = noticeAccentFor(chromeContent = Color.White)
        listOf(Color(0xFF0B1E22), Color(0xFF1B2A3A), Color(0xFF000000)).forEach { assertTrue(contrast(accent, it) >= 4.5f) }
    }

    @Test
    fun onLightChromeTheAccentIsDarkEnoughToStandOut() {
        val accent = noticeAccentFor(chromeContent = Color.Black)
        listOf(Color.White, Color(0xFFF2F2F2), Color(0xFFE0E0E0)).forEach { assertTrue(contrast(accent, it) >= 4.5f) }
    }

    @Test
    fun theAccentIsNeverTheRedOfTheUnreadCount() {
        val unreadRed = Color(0xFFE53935)
        assertTrue(noticeAccentFor(Color.White) != unreadRed && noticeAccentFor(Color.Black) != unreadRed)
    }
}
