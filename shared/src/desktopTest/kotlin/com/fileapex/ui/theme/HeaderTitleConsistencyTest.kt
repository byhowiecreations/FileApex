package com.fileapex.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.AppTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app title and its subtitle read the same in Simple, Clean, Flux Glass, Kinetic Sphere and Freestyle. */
class HeaderTitleConsistencyTest {
    private fun styles(theme: AppTheme): Pair<TextStyle, TextStyle> {
        var pair: Pair<TextStyle, TextStyle>? = null
        val scene = ImageComposeScene(width = 40, height = 40, density = Density(1f)) {
            FileApexTheme(appTheme = theme) { pair = fileApexHeaderTitleStyle() to fileApexHeaderSubtitleStyle() }
        }
        scene.render()
        scene.close()
        return pair!!
    }

    @Test
    fun `every theme uses the same title and subtitle style`() {
        val reference = styles(AppTheme.CLEAN)
        for (theme in AppTheme.entries) {
            val (title, subtitle) = styles(theme)
            assertEquals("$theme title size", reference.first.fontSize, title.fontSize)
            assertEquals("$theme title weight", reference.first.fontWeight, title.fontWeight)
            assertEquals("$theme title spacing", reference.first.letterSpacing, title.letterSpacing)
            assertEquals("$theme subtitle size", reference.second.fontSize, subtitle.fontSize)
            assertEquals("$theme subtitle weight", reference.second.fontWeight, subtitle.fontWeight)
        }
    }

    @Test
    fun `the title is 22 sp bold and the subtitle 14 sp`() {
        val (title, subtitle) = styles(AppTheme.FLUX_GLASS)
        assertEquals(22.sp, title.fontSize)
        assertEquals(FontWeight.Bold, title.fontWeight)
        assertEquals(14.sp, subtitle.fontSize)
    }

    @Test
    fun `the header files use the shared styles and no private title sizes`() {
        val base = File("src/commonMain/kotlin/com/fileapex/ui/adaptive")
        for (name in listOf("FluxGlassHeader.kt", "CompactPrimaryShell.kt", "simple/SimpleTopBar.kt")) {
            val source = File(base, name).readText()
            assertTrue("$name should use fileApexHeaderTitleStyle", source.contains("fileApexHeaderTitleStyle()"))
            assertFalse("$name sets its own title size", source.contains("headlineLarge"))
        }
    }
}
