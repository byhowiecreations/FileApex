package com.fileapex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.DesktopNavPosition
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.isFileApexTiledChrome

/**
 * Custom panel-position icon displaying the Left / Bottom / Right bar location that the next click activates.
 */
fun navDockPositionIcon(targetPosition: DesktopNavPosition): ImageVector {
    val name = "NavDock_${targetPosition.name}"
    val ink = SolidColor(Color.Black)
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Outer rounded frame
        path(
            stroke = ink,
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5f, 4f)
            horizontalLineTo(19f)
            arcTo(2f, 2f, 0f, false, true, 21f, 6f)
            verticalLineTo(18f)
            arcTo(2f, 2f, 0f, false, true, 19f, 20f)
            horizontalLineTo(5f)
            arcTo(2f, 2f, 0f, false, true, 3f, 18f)
            verticalLineTo(6f)
            arcTo(2f, 2f, 0f, false, true, 5f, 4f)
            close()
        }
        // Highlighted bar indicating next dock position (L / B / R)
        when (targetPosition) {
            DesktopNavPosition.Left -> path(fill = ink) {
                moveTo(5.2f, 6f)
                horizontalLineTo(8.5f)
                verticalLineTo(18f)
                horizontalLineTo(5.2f)
                close()
            }
            DesktopNavPosition.Bottom -> path(fill = ink) {
                moveTo(5.2f, 14.8f)
                horizontalLineTo(18.8f)
                verticalLineTo(18f)
                horizontalLineTo(5.2f)
                close()
            }
            DesktopNavPosition.Right -> path(fill = ink) {
                moveTo(15.5f, 6f)
                horizontalLineTo(18.8f)
                verticalLineTo(18f)
                horizontalLineTo(15.5f)
                close()
            }
        }
    }.build()
}

@Composable
fun NavDockPositionToggle(
    modifier: Modifier = Modifier,
    iconTint: Color = LocalContentColor.current
) {
    val current by FileApexServices.settings.desktopNavPosition.collectAsState()
    val next = current.next()
    val tooltipText = stringRes(
        when (next) {
            DesktopNavPosition.Left -> "tooltip_nav_pos_left"
            DesktopNavPosition.Bottom -> "tooltip_nav_pos_bottom"
            DesktopNavPosition.Right -> "tooltip_nav_pos_right"
        }
    )
    DesktopHoverTooltip(text = tooltipText) {
        if (isFileApexTiledChrome()) {
            Box(
                modifier = modifier
                    .size(40.dp)
                    .clickable { FileApexServices.settings.setDesktopNavPosition(next) },
                contentAlignment = Alignment.Center
            ) {
                JadedRaisedTile(tileSize = 28.dp) {
                    Icon(
                        imageVector = navDockPositionIcon(next),
                        contentDescription = tooltipText,
                        tint = fileApexTileTint(),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else {
            IconButton(
                onClick = { FileApexServices.settings.setDesktopNavPosition(next) },
                modifier = modifier
            ) {
                Icon(
                    imageVector = navDockPositionIcon(next),
                    contentDescription = tooltipText,
                    tint = iconTint
                )
            }
        }
    }
}
