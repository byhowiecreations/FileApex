package com.fileapex.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.ui.theme.LocalJadedHazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import com.fileapex.i18n.stringRes
import com.fileapex.ui.DesktopHoverTooltip
import com.fileapex.ui.theme.isFileApexCleanCurved
import com.fileapex.ui.theme.isFileApexCleanModern
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits

@Composable
internal fun JadedRaisedTile(
    tileSize: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val tile = RoundedCornerShape(8.dp)
    if (isFileApexCleanCurved()) {
        Box(
            modifier = modifier
                .size(tileSize)
                .clip(tile)
                .background(Color.White.copy(alpha = 0.18f))
                .border(1.dp, Color.White.copy(alpha = 0.35f), tile),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
        return
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(tileSize)
                .blur(8.dp)
                .background(Color(0x736366F1), tile)
        )
        Box(
            modifier = Modifier
                .size(tileSize)
                .clip(tile)
                .background(Color(0x596366F1))
                .border(1.dp, Color(0x73A0AAFF), tile),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
internal fun Modifier.jadedCompactHeaderPanel(): Modifier {
    val shape = RoundedCornerShape(20.dp)
    val haze = LocalJadedHazeState.current
    return this
        .padding(horizontal = 10.dp, vertical = 4.dp)
        .clip(shape)
        .then(
            if (haze != null) {
                Modifier.hazeEffect(
                    state = haze,
                    style = HazeStyle(
                        backgroundColor = Color.Transparent,
                        tints = listOf(HazeTint(Color.White.copy(alpha = 0.16f))),
                        blurRadius = 24.dp,
                        noiseFactor = 0.04f
                    )
                )
            } else {
                Modifier
            }
        )
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.22f),
                1f to Color.White.copy(alpha = 0.10f)
            )
        )
        .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
}

@Composable
fun FileApexPowerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    DesktopHoverTooltip(text = stringRes("tooltip_quit")) {
        if (isFileApexCleanModern() || LocalAppTheme.current.traits.fluxSurfaces) {
            ModernHeaderPowerButton(onClick = onClick, modifier = modifier)
        } else {
            LegacyHeaderPowerButton(onClick = onClick, modifier = modifier)
        }
    }
}
