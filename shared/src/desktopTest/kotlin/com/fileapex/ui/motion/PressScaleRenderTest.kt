package com.fileapex.ui.motion

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressScaleRenderTest {
    private fun redPixelsWhilePressed(reduced: Boolean): Pair<Int, Int> {
        val source = MutableInteractionSource()
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(80.dp).pressScale(source).background(Color.Red))
                }
            }
        }
        var t = 0L
        fun count(): Int {
            t += 16_000_000L
            val image = scene.render(t)
            val bitmap = Bitmap.makeFromImage(image)
            try {
                var n = 0
                for (y in 0 until 100) for (x in 0 until 100) if ((bitmap.getColor(x, y) shr 16) and 0xFF > 128) n++
                return n
            } finally {
                bitmap.close(); image.close()
            }
        }
        repeat(3) { count() }
        val before = count()
        source.tryEmit(PressInteraction.Press(Offset.Zero))
        var after = 0
        repeat(25) { after = count() }
        scene.close()
        return before to after
    }

    @Test
    fun `a pressed tile shrinks to about 97 percent`() {
        val (before, after) = redPixelsWhilePressed(reduced = false)
        assertEquals(6400, before)
        assertTrue("pressed area $after is not about 0.97 squared of $before", after in 5900..6100)
    }

    @Test
    fun `a pressed tile does not move when motion is reduced`() {
        val (before, after) = redPixelsWhilePressed(reduced = true)
        assertEquals(before, after)
    }
}
