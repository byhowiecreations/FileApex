package com.fileapex.ui.theme

import androidx.compose.ui.graphics.luminance
import com.fileapex.data.settings.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowBackdropTest {
    @Test
    fun `the colour behind the window is never the default white for a dark theme`() {
        for (theme in listOf(AppTheme.SIMPLE, AppTheme.FLUX_GLASS, AppTheme.KINETIC_SPHERE, AppTheme.FREESTYLE, AppTheme.CAROUSEL)) {
            val backdrop = theme.windowBackdrop()
            assertTrue("$theme backdrop is too bright: $backdrop", backdrop.luminance() < 0.2f)
            assertEquals("$theme backdrop must be opaque", 1f, backdrop.alpha, 0.0001f)
        }
    }

    @Test
    fun `the light Clean theme keeps its own canvas colour`() {
        assertEquals(CleanModernPalette.CanvasBackdrop, AppTheme.CLEAN.windowBackdrop())
    }
}
