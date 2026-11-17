package com.fileapex.ui.transfer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.ui.theme.FileApexTheme
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRowRenderTest {
    private fun model(state: TransferRowState, progress: Float?) = TransferRowModel(
        "r", "report.pdf", "Mac", state, progress, "50%", null, null, canCancel = true, canRetry = false, canRemove = false, isSend = true
    )

    private fun render(model: TransferRowModel, density: TransferRowDensity): Bitmap {
        val scene = ImageComposeScene(width = 600, height = 160, density = Density(1f)) {
            FileApexTheme(appTheme = AppTheme.CLEAN) {
                Box(Modifier.fillMaxSize()) { TransferRow(model, density, onCancel = {}, onRetry = {}, onRemove = {}) }
            }
        }
        scene.render(0L); scene.render(16_000_000L)
        val image = scene.render(32_000_000L)
        val bitmap = Bitmap.makeFromImage(image)
        image.close(); scene.close()
        return bitmap
    }

    private fun differing(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until 160) for (x in 0 until 600) if (a.getColor(x, y) != b.getColor(x, y)) n++
        return n
    }

    @Test
    fun `the progress bar fill follows the fraction`() {
        for (density in TransferRowDensity.entries) {
            val half = render(model(TransferRowState.Active, 0.5f), density)
            val full = render(model(TransferRowState.Active, 1f), density)
            val same = render(model(TransferRowState.Active, 0.5f), density)
            assertEquals("$density renders deterministically", 0, differing(half, same))
            assertTrue("$density bar did not change with progress", differing(half, full) > 150)
        }
    }

    @Test
    fun `a finished row has no bar`() {
        val active = render(model(TransferRowState.Active, 0.5f), TransferRowDensity.Standard)
        val done = render(model(TransferRowState.Done, null), TransferRowDensity.Standard)
        assertTrue(differing(active, done) > 150)
    }
}
