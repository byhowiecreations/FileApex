package com.fileapex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.traits
import com.fileapex.ui.theme.CarouselPalette
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.KineticStyleLook

internal data class ExplorerInk(
    val title: Color,
    val muted: Color,
    val accent: Color,
    val folderTint: Color,
    val paneSelected: Color,
    val listSelected: Color,
    val listIdle: Color,
    val divider: Color,
    val paneBackground: Color,
    val gridFill: (Boolean) -> Color,
    val gridBorder: ((Boolean) -> BorderStroke?)?,
    val gridCorner: Dp,
    val selectionBar: Color?,
    val jadedGlyphs: Boolean,
    val styledKinetic: Boolean
)

@Composable
internal fun explorerInk(): ExplorerInk {
    val theme = LocalAppTheme.current
    val kinetic = if (theme.traits.orbitalHome) LocalKineticStyle.current else null
    if (kinetic == KineticStyle.JADED_STEEL) {
        return ExplorerInk(
            title = KineticStyleLook.ink,
            muted = Color.White.copy(alpha = 0.60f),
            accent = KineticStyleLook.jadedFolderList,
            folderTint = KineticStyleLook.jadedFolderList,
            paneSelected = Color(0x3896BEC8),
            listSelected = KineticStyleLook.jadedCardSelected,
            listIdle = Color.Transparent,
            divider = KineticStyleLook.jadedDivider,
            paneBackground = KineticStyleLook.jadedPane,
            gridFill = { selected ->
                if (selected) KineticStyleLook.jadedCardSelected else KineticStyleLook.jadedCard
            },
            gridBorder = null,
            gridCorner = 16.dp,
            selectionBar = KineticStyleLook.jadedSelectionBar,
            jadedGlyphs = true,
            styledKinetic = true
        )
    }
    if (theme == AppTheme.CAROUSEL) {
        return ExplorerInk(
            title = Color(0xFFE8E8E8),
            muted = CarouselPalette.textMuted,
            accent = CarouselPalette.accent,
            folderTint = CarouselPalette.accent,
            paneSelected = CarouselPalette.accent.copy(alpha = 0.12f),
            listSelected = CarouselPalette.accent.copy(alpha = 0.10f),
            listIdle = Color.Transparent,
            divider = Color.White.copy(alpha = 0.10f),
            paneBackground = Color.White.copy(alpha = 0.05f),
            gridFill = { selected -> if (selected) CarouselPalette.accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f) },
            gridBorder = { selected -> BorderStroke(1.dp, if (selected) CarouselPalette.accent else Color.White.copy(alpha = 0.16f)) },
            gridCorner = 20.dp,
            selectionBar = null,
            jadedGlyphs = false,
            styledKinetic = true
        )
    }
    val flux = theme.traits.fluxSurfaces
    val scheme = MaterialTheme.colorScheme
    // Simple sits on the dark steel background, where the standard dark teal would not show.
    val teal = if (theme == AppTheme.SIMPLE) scheme.primary else FileApexTeal
    return ExplorerInk(
        title = if (flux) Color.White else scheme.onSurface,
        muted = if (flux) FluxGlassPalette.explorerMuted else scheme.onSurfaceVariant,
        accent = if (flux) FluxGlassPalette.accent else scheme.primary,
        folderTint = if (flux) FluxGlassPalette.accent else teal,
        paneSelected = if (flux) FluxGlassPalette.accentFill else teal.copy(alpha = 0.14f),
        listSelected = if (flux) FluxGlassPalette.accentFill else teal.copy(alpha = 0.10f),
        listIdle = if (flux) Color.Transparent else scheme.surface,
        divider = if (flux) Color.White.copy(alpha = 0.12f) else scheme.outlineVariant,
        paneBackground = scheme.surfaceVariant.copy(alpha = 0.35f),
        gridFill = { selected ->
            if (flux) {
                if (selected) FluxGlassPalette.accentFill else FluxGlassPalette.unselectedFill
            } else {
                if (selected) teal.copy(alpha = 0.12f) else scheme.surfaceVariant.copy(alpha = 0.45f)
            }
        },
        gridBorder = if (flux) {
            { selected ->
                BorderStroke(1.dp, if (selected) FluxGlassPalette.accent else Color.White.copy(alpha = 0.15f))
            }
        } else {
            null
        },
        gridCorner = 12.dp,
        selectionBar = null,
        jadedGlyphs = false,
        styledKinetic = false
    )
}
