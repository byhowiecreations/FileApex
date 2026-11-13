package com.fileapex.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import fileapex.shared.generated.resources.Res
import fileapex.shared.generated.resources.jaded_bg
import org.jetbrains.compose.resources.painterResource

object KineticStyleLook {
    val jade = Color(0xFF00E676)
    val steel = Color(0xFF8DB8F5)
    val cyan = Color(0xFF00E5FF)
    val jadedPane = Color(0xCC121E28)
    val jadedCard = Color(0x55384850)
    /** Floating device menu. List cards stay translucent; this panel has to hide the labels under it. */
    val jadedMenu = Color(0xFF243632)
    val jadedCardSelected = Color(0x8844545C)
    val jadedSelected = Color(0xFF152028)
    val jadedDivider = Color(0x14FFFFFF)
    val jadedFolderList = Color(0xFF4A8E86)
    val jadedSelectionBar = Color(0xFF6366F1)
    val jadedCardEdge = Color(0x66D7E6E8)
    val ink = Color(0xFFF4F7F6)
    val muted = Color(0xFFB7C4BE)
}

/** Modern dark slate-obsidian backdrop with a soft upper-left emerald-teal atmospheric glow. */
@Composable
fun Modifier.simpleSteelBackground(): Modifier =
    background(com.fileapex.ui.adaptive.simple.SimpleModernPalette.BackgroundDark)
        .background(
            androidx.compose.ui.graphics.Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to com.fileapex.ui.adaptive.simple.SimpleModernPalette.GlowTeal,
                    0.42f to com.fileapex.ui.adaptive.simple.SimpleModernPalette.GlowMid,
                    1.0f to androidx.compose.ui.graphics.Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(80f, 90f),
                radius = 950f
            )
        )

@Composable
fun JadedSteelWash(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    Image(
        painter = painterResource(Res.drawable.jaded_bg),
        contentDescription = null,
        modifier = modifier
            .fillMaxSize()
            .then(if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier),
        contentScale = ContentScale.Crop
    )
}
