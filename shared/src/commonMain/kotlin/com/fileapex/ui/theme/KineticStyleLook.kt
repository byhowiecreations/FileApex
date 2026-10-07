package com.fileapex.ui.theme

import androidx.compose.foundation.Image
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
    val jadedBackground = Color(0xFF0F1412)
    val jadedSurface = Color(0xFF16201D)
    val jadedPane = Color(0xCC121E28)
    val jadedCard = Color(0x55384850)
    /** Floating device menu. List cards stay translucent; this panel has to hide the labels under it. */
    val jadedMenu = Color(0xFF243632)
    val jadedCardSelected = Color(0x8844545C)
    val jadedSelected = Color(0xFF152028)
    val jadedDivider = Color(0x14FFFFFF)
    val jadedFolderList = Color(0xFF4A8E86)
    val jadedFolderTeal = Color(0xFF5BB5AE)
    val jadedFolderBlue = Color(0xFF7ECFF2)
    val jadedSelectionBar = Color(0xFF6366F1)
    val jadedCardEdge = Color(0x66D7E6E8)
    val ink = Color(0xFFF4F7F6)
    val muted = Color(0xFFB7C4BE)
}

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
