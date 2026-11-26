package com.fileapex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** Dialogs, sheets and menus take their surface from the theme instead of Material's default lavender or grey. */
class DialogSurfaceTest {
    private fun dialogSurface(theme: AppTheme): Color {
        var color = Color.Unspecified
        val scene = ImageComposeScene(width = 40, height = 40, density = Density(1f)) {
            FileApexTheme(appTheme = theme) { color = MaterialTheme.colorScheme.surfaceContainerHigh }
        }
        scene.render()
        scene.close()
        return color
    }

    @Test
    fun `clean dialogs are white`() = assertEquals(Color.White, dialogSurface(AppTheme.CLEAN))

    @Test
    fun `flux glass and kinetic dialogs use the dark glass card color`() {
        assertEquals(FluxGlassPalette.notesCard, dialogSurface(AppTheme.FLUX_GLASS))
        assertEquals(FluxGlassPalette.notesCard, dialogSurface(AppTheme.KINETIC_SPHERE))
        assertEquals(FluxGlassPalette.notesCard, dialogSurface(AppTheme.FREESTYLE))
    }

    @Test
    fun `carousel dialogs use its raised card color`() {
        assertEquals(CarouselPalette.cardRaised, dialogSurface(AppTheme.CAROUSEL))
    }
}
