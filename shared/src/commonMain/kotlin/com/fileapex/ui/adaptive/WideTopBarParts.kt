package com.fileapex.ui.adaptive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.fileapex.ui.tools.ToolsHeaderButton
import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.isFileApexTiledChrome
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.ExplorerIcon
import com.fileapex.i18n.stringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Refresh
import com.fileapex.ui.theme.KineticStyleLook
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fileapex.domain.transfer.TransferActivityGuard
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.ui.ExplorerHeaderCommands
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.fileApexChromeContentColor
import androidx.compose.runtime.setValue

@Composable
internal fun RowScope.TransferStatsPill(onOpenTransferQueue: () -> Unit) {
    val liveStats by TransferActivityGuard.statsFlow.collectAsState()
    if (liveStats.isActive) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(FileApexTeal.copy(alpha = 0.20f))
                .clickable(onClick = onOpenTransferQueue)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                progress = { liveStats.progress },
                modifier = Modifier.size(12.dp),
                color = FileApexTeal,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(6.dp))
            val rateText = buildList {
                if (liveStats.speedFormatted.isNotBlank()) add(liveStats.speedFormatted)
                if (liveStats.etaFormatted.isNotBlank()) add(liveStats.etaFormatted)
                val percent = (liveStats.progress * 100).toInt().coerceIn(0, 100)
                add("$percent%")
            }.joinToString(" • ")
            Text(
                text = if (rateText.isNotBlank()) rateText else stringRes("sending"),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = fileApexChromeContentColor()
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
    }
}

@Composable
internal fun RowScope.ExplorerHeaderCommandButtons(commands: ExplorerHeaderCommands, jadedHeader: Boolean) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            TextButton(
                onClick = commands.onSelect,
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = stringRes("select"),
                    color = if (jadedHeader) KineticStyleLook.steel else fileApexChromeContentColor(),
                    fontWeight = FontWeight.SemiBold
                )
            }
            commands.onOpenTools?.let { onOpenTools ->
                ToolsHeaderButton(
                    onClick = onOpenTools,
                    tint = if (jadedHeader) KineticStyleLook.steel else fileApexChromeContentColor(),
                    modifier = Modifier.size(40.dp)
                )
            }
            commands.onPaste?.let { onPaste ->
                TextButton(
                    onClick = onPaste,
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringRes("paste"),
                        color = if (jadedHeader) KineticStyleLook.steel else fileApexChromeContentColor(),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
}

@Composable
internal fun RowScope.RefreshExplorerButton(onRefreshExplorer: () -> Unit, isExplorerRefreshing: Boolean, headerIconTint: Color) {
        if (isFileApexTiledChrome()) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(enabled = !isExplorerRefreshing, onClick = onRefreshExplorer),
                contentAlignment = Alignment.Center
            ) {
                JadedRaisedTile(tileSize = 28.dp) {
                    if (isExplorerRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = fileApexTileTint()
                        )
                    } else {
                        Icon(
                            imageVector = explorerIcon(ExplorerIcon.Refresh),
                            contentDescription = stringRes("refresh"),
                            tint = fileApexTileTint(),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        } else {
            IconButton(
                onClick = onRefreshExplorer,
                enabled = !isExplorerRefreshing,
                modifier = Modifier.size(40.dp)
            ) {
                if (isExplorerRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = explorerIcon(ExplorerIcon.Refresh),
                        contentDescription = stringRes("refresh"),
                        tint = headerIconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
}

/** Themes whose wide header leaves out the "Paired Devices" caption so the alert line has room. */
internal val HEADER_WITHOUT_PAIRED_TITLE = setOf(
    com.fileapex.data.settings.AppTheme.CLEAN,
    com.fileapex.data.settings.AppTheme.FLUX_GLASS,
    com.fileapex.data.settings.AppTheme.FREESTYLE,
    com.fileapex.data.settings.AppTheme.CAROUSEL
)
