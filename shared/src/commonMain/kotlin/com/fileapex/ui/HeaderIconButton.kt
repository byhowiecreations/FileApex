package com.fileapex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.isFileApexTiledChrome

/** The one icon-only header button: a raised tile on tiled chrome, a plain icon button elsewhere. */
@Composable
internal fun HeaderIconButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    DesktopHoverTooltip(text = label) {
        if (isFileApexTiledChrome()) {
            Box(modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
                JadedRaisedTile(tileSize = 28.dp) {
                    Icon(icon, contentDescription = label, tint = fileApexTileTint(), modifier = Modifier.size(18.dp))
                }
            }
        } else {
            IconButton(onClick = onClick, modifier = modifier.size(40.dp)) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
    }
}
