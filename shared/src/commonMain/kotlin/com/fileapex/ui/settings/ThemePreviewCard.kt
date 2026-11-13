package com.fileapex.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import fileapex.shared.generated.resources.Res
import fileapex.shared.generated.resources.theme_thumb_carousel
import fileapex.shared.generated.resources.theme_thumb_clean
import fileapex.shared.generated.resources.theme_thumb_flux_glass
import fileapex.shared.generated.resources.theme_thumb_freestyle
import fileapex.shared.generated.resources.theme_thumb_kinetic_sphere
import fileapex.shared.generated.resources.theme_thumb_simple
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

data class ThemePreviewSpec(
    val theme: AppTheme,
    val backgroundHex: Long,
    val chromeHex: Long,
    val accentHex: Long,
    val cardHex: Long,
    val drawableName: String
)

object ThemePreviewCatalog {
    fun specFor(theme: AppTheme): ThemePreviewSpec = when (theme) {
        AppTheme.SIMPLE -> ThemePreviewSpec(theme, 0xFF0A1115, 0xFF1A2C30, 0xFF2DD4BF, 0xFF152227, "theme_thumb_simple")
        AppTheme.CLEAN -> ThemePreviewSpec(theme, 0xFFFFFFFF, 0xFF0F766E, 0xFF0F766E, 0xFFF1F5F9, "theme_thumb_clean")
        AppTheme.CAROUSEL -> ThemePreviewSpec(theme, 0xFF0E1418, 0xFF1D2830, 0xFF5EEAD4, 0xFF1A252C, "theme_thumb_carousel")
        AppTheme.KINETIC_SPHERE -> ThemePreviewSpec(theme, 0xFF02050B, 0xFF0C1524, 0xFF00E5FF, 0xFF132238, "theme_thumb_kinetic_sphere")
        AppTheme.FREESTYLE -> ThemePreviewSpec(theme, 0xFF000000, 0xFF121212, 0xFF5BB5AE, 0xFF1E1E1E, "theme_thumb_freestyle")
        AppTheme.FLUX_GLASS -> ThemePreviewSpec(theme, 0xFF09131E, 0xFF162535, 0xFF60A5FA, 0xFF1F3349, "theme_thumb_flux_glass")
    }

    fun allSpecs(): List<ThemePreviewSpec> = AppTheme.entries.map(::specFor)
}

@Composable
internal fun ThemePreviewThumbnail(
    theme: AppTheme,
    modifier: Modifier = Modifier
) {
    val spec = ThemePreviewCatalog.specFor(theme)
    val bg = Color(spec.backgroundHex)
    val accent = Color(spec.accentHex)

    Surface(
        modifier = modifier.size(width = 62.dp, height = 90.dp),
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
    ) {
        Image(
            painter = painterResource(previewDrawable(theme)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun previewDrawable(theme: AppTheme): DrawableResource = when (theme) {
    AppTheme.SIMPLE -> Res.drawable.theme_thumb_simple
    AppTheme.CLEAN -> Res.drawable.theme_thumb_clean
    AppTheme.CAROUSEL -> Res.drawable.theme_thumb_carousel
    AppTheme.KINETIC_SPHERE -> Res.drawable.theme_thumb_kinetic_sphere
    AppTheme.FREESTYLE -> Res.drawable.theme_thumb_freestyle
    AppTheme.FLUX_GLASS -> Res.drawable.theme_thumb_flux_glass
}
