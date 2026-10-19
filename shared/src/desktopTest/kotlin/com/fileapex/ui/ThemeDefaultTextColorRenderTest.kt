package com.fileapex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.backgroundBrush
import com.fileapex.data.settings.traits
import com.fileapex.ui.theme.FileApexTheme
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test

/** Renders text that sets no color on every theme and style, the way the glass themes draw (transparent container), and checks it can be read. */
class ThemeDefaultTextColorRenderTest {
    private fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    private fun contrast(a: Double, b: Double): Double = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    private fun render(theme: AppTheme, kinetic: KineticStyle, name: String): Double {
        val scene = ImageComposeScene(width = 480, height = 640, density = Density(1f)) {
            FileApexTheme(appTheme = theme, kineticStyle = kinetic) {
                val brush = theme.backgroundBrush()
                val base = if (brush != null) Modifier.background(brush) else Modifier
                Box(base.fillMaxSize().background(Color(0xFF14202A).takeIf { brush == null && theme.traits.glassChrome } ?: Color.Transparent)) {
                    Scaffold(containerColor = if (theme.traits.glassChrome) Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background) { padding ->
                        androidx.compose.material3.Text("Text with no color of its own, on a transparent container.", modifier = Modifier.padding(padding).padding(16.dp))
                    }
                }
            }
        }
        val image = scene.render()
        scene.close()
        val out = File(System.getProperty("java.io.tmpdir"), "default-text-$name.png")
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        // Title and body sit in the top of the screen; the worst text pixel in each row is compared with that row's own background.
        var best = 1.0
        for (y in 15 until 120) {
            val bg = luminance(bitmap.getColor(2, y))
            for (x in 10 until 470) best = maxOf(best, contrast(luminance(bitmap.getColor(x, y)), bg))
        }
        println("default text render $name -> ${out.absolutePath} contrast=$best")
        return best
    }

    @Test
    fun textIsReadableOnEveryTheme() {
        val cases = listOf(
            Triple(AppTheme.SIMPLE, KineticStyle.SPACE, "simple"),
            Triple(AppTheme.CLEAN, KineticStyle.SPACE, "clean"),
            Triple(AppTheme.FLUX_GLASS, KineticStyle.SPACE, "flux"),
            Triple(AppTheme.KINETIC_SPHERE, KineticStyle.SPACE, "kinetic-space"),
            Triple(AppTheme.KINETIC_SPHERE, KineticStyle.JADED_STEEL, "kinetic-jaded"),
            Triple(AppTheme.FREESTYLE, KineticStyle.SPACE, "freestyle")
        )
        for ((theme, style, name) in cases) {
            assertTrue("$name text is hard to read", render(theme, style, name) >= 4.5)
        }
    }
}
