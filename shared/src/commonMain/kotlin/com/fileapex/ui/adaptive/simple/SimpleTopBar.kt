package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.i18n.stringRes
import com.fileapex.platform.isDesktopHost
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.DesktopHoverTooltip
import com.fileapex.ui.ExplorerHeaderCommands
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.ExplorerViewModeToggle
import com.fileapex.ui.NavDockPositionToggle
import com.fileapex.ui.QueuedFilesButton
import com.fileapex.ui.adaptive.FileApexPowerButton
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.theme.fileApexChromeBottomEdge
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.fileApexFloatingChrome
import com.fileapex.ui.theme.fileApexHeaderActionTint
import com.fileapex.ui.theme.isFileApexCleanCurved
import com.fileapex.ui.theme.isFileApexSimpleModern

@Composable
internal fun SimpleTopBar(
    homeSelected: Boolean,
    onOpenNotes: (() -> Unit)?,
    onHome: () -> Unit,
    onExit: () -> Unit,
    onOpenTransferQueue: () -> Unit,
    explorerCommands: ExplorerHeaderCommands?,
    splitAvailable: Boolean,
    onRefreshExplorer: (() -> Unit)?,
    explorerRefreshing: Boolean
) {
    val explorerViewMode by FileApexServices.settings.explorerViewMode.collectAsState()
    val tint = fileApexHeaderActionTint(onChromeBar = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fileApexChromeBottomEdge()
            .then(
                if (isFileApexCleanCurved()) {
                    Modifier.fileApexFloatingChrome(
                        fileApexChromeContainerColor(),
                        outer = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    )
                } else {
                    Modifier.background(fileApexChromeContainerColor())
                }
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FileApex",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = fileApexChromeContentColor()
        )
        Spacer(modifier = Modifier.width(8.dp))
        DesktopHoverTooltip(text = stringRes("tooltip_home")) {
            val activeSimpleHome = homeSelected && isFileApexSimpleModern()
            val homeShape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(homeShape)
                    .background(if (activeSimpleHome) SimpleTopBarHomeStyle.activeContainerColor else Color.Transparent)
                    .then(
                        if (activeSimpleHome) {
                            Modifier.border(1.dp, SimpleTopBarHomeStyle.activeBorderColor, homeShape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable(onClick = onHome),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Home,
                    contentDescription = stringRes("home"),
                    tint = if (activeSimpleHome) SimpleTopBarHomeStyle.activeIconColor else tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) { onOpenNotes?.let { com.fileapex.ui.adaptive.HeaderAlertTicker(it) } }
        val liveStats by TransferActivityGuard.statsFlow.collectAsState()
        if (liveStats.isActive) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(onClick = onOpenTransferQueue)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    progress = { liveStats.progress },
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                val rateText = buildList {
                    if (liveStats.speedFormatted.isNotBlank()) add(liveStats.speedFormatted)
                    if (liveStats.etaFormatted.isNotBlank()) add(liveStats.etaFormatted)
                    add("${(liveStats.progress * 100).toInt().coerceIn(0, 100)}%")
                }.joinToString(" • ")
                Text(
                    text = rateText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = fileApexChromeContentColor()
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
        DesktopHoverTooltip(text = stringRes("tooltip_queue")) {
            QueuedFilesButton(onClick = onOpenTransferQueue, iconTint = tint)
        }
        explorerCommands?.let { commands ->
            TextButton(onClick = commands.onSelect, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringRes("select"), color = fileApexChromeContentColor(), fontWeight = FontWeight.SemiBold)
            }
            commands.onPaste?.let { onPaste ->
                TextButton(onClick = onPaste, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringRes("paste"), color = fileApexChromeContentColor(), fontWeight = FontWeight.SemiBold)
                }
            }
            if (splitAvailable) {
                val splitActive = explorerViewMode == ExplorerViewMode.Split
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            val next = if (splitActive) ExplorerViewMode.List else ExplorerViewMode.Split
                            FileApexServices.settings.setExplorerViewMode(next)
                        },
                    shape = RoundedCornerShape(50),
                    color = if (splitActive) SimpleModernPalette.AccentMint else SimpleModernPalette.PillTint
                ) {
                    Text(
                        text = stringRes("split_view_label"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (splitActive) SimpleModernPalette.AccentOnMint else SimpleModernPalette.AccentMintSoft,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            val viewTooltip = stringRes(if (explorerViewMode == ExplorerViewMode.List) "switch_to_grid" else "switch_to_list")
            DesktopHoverTooltip(text = viewTooltip) {
                ExplorerViewModeToggle(
                    viewMode = explorerViewMode,
                    onToggle = {
                        FileApexServices.settings.setExplorerViewMode(explorerViewMode.toggled())
                    },
                    includeSplit = false,
                    iconTint = tint,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        if (isDesktopHost()) {
            NavDockPositionToggle(modifier = Modifier.size(40.dp), iconTint = tint)
        }
        if (onRefreshExplorer != null) {
            DesktopHoverTooltip(text = stringRes("refresh")) {
                IconButton(onClick = onRefreshExplorer, enabled = !explorerRefreshing, modifier = Modifier.size(40.dp)) {
                    if (explorerRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = explorerIcon(ExplorerIcon.Refresh),
                            contentDescription = stringRes("refresh"),
                            tint = tint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
        DesktopHoverTooltip(text = stringRes("tooltip_quit")) {
            FileApexPowerButton(onClick = onExit)
        }
    }
}
