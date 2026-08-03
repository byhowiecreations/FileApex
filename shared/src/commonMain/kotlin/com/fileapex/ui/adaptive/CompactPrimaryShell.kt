package com.fileapex.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.ui.HomeTab
import com.fileapex.ui.FileApexBottomBar
import com.fileapex.ui.theme.fileApexChromeBottomEdge
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeContentColor

/** Shared compact home header metrics — keeps Devices, Settings, and explorer bands aligned. */
object CompactHomeChrome {
    /** Fixed teal strip height on every tab (IconButton row — same as Devices power affordance). */
    val tealStripHeight = 56.dp
    val titleBandHorizontalPadding = 20.dp
    val titleBandVerticalPadding = 16.dp
    val eyebrowHeadlineGap = 4.dp
    /** Matches Paired Devices + FileApex two-line band content height. */
    val titleBandMinHeight = 86.dp
}

/**
 * Compact-mode primary chrome: teal strip (optional exit) + bottom navigation.
 * Screen content supplies the white title band via [CompactHomeTitleBand].
 */
@Composable
fun CompactPrimaryShell(
    selectedTab: HomeTab,
    onMainHomeScreen: Boolean = true,
    showExitPower: Boolean,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    onExitApp: () -> Unit,
    tealStripActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            FileApexBottomBar(
                selected = selectedTab,
                onMainHomeScreen = onMainHomeScreen,
                onDevices = onDevices,
                onFiles = onFiles,
                onSettings = onSettings
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // In content — not Scaffold topBar (Compose Desktop collapses an empty topBar slot).
            CompactTealStrip(
                showExitPower = showExitPower,
                onExitClick = onExitApp,
                actions = tealStripActions
            )
            content()
        }
    }
}

@Composable
fun CompactTealStrip(
    showExitPower: Boolean,
    onExitClick: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CompactHomeChrome.tealStripHeight)
            .fileApexChromeBottomEdge()
            .background(fileApexChromeContainerColor())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        actions()
        if (showExitPower) {
            IconButton(onClick = onExitClick) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = "Exit FileApex",
                    tint = fileApexChromeContentColor()
                )
            }
        } else {
            // Reserve the same trailing space as the Devices power button row.
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

/** Layout style for [CompactHomeTitleBand]. */
enum class CompactHomeTitleStyle {
    /** Small eyebrow line + large headline (Devices / Settings root). */
    Prominent,
    /** Medium title + optional detail subtitle (file explorer). */
    Detail
}

@Composable
fun CompactDevicesTitleBand(
    actions: @Composable RowScope.() -> Unit = {}
) {
    CompactHomeTitleBand(
        primaryLine = "Paired Devices",
        secondaryLine = "FileApex",
        style = CompactHomeTitleStyle.Prominent,
        actions = actions
    )
}

@Composable
fun CompactHomeTitleBand(
    primaryLine: String,
    secondaryLine: String? = null,
    style: CompactHomeTitleStyle = CompactHomeTitleStyle.Detail,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    CompactHomeTitleBandRow(modifier = modifier, actions = actions) {
        when (style) {
            CompactHomeTitleStyle.Prominent -> {
                Text(
                    text = primaryLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(CompactHomeChrome.eyebrowHeadlineGap))
                Text(
                    text = secondaryLine.orEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    style = compactHomeHeadlineStyle(),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            CompactHomeTitleStyle.Detail -> {
                Text(
                    text = primaryLine,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!secondaryLine.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(CompactHomeChrome.eyebrowHeadlineGap))
                    Text(
                        text = secondaryLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactHomeTitleBandRow(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CompactHomeChrome.titleBandMinHeight)
            .background(MaterialTheme.colorScheme.surface)
            .padding(
                horizontal = CompactHomeChrome.titleBandHorizontalPadding,
                vertical = CompactHomeChrome.titleBandVerticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            titleContent()
        }
        actions()
    }
}

@Composable
private fun compactHomeHeadlineStyle() =
    MaterialTheme.typography.headlineLarge.copy(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp
    )

/** @deprecated Use [CompactHomeTitleBand] with [CompactHomeTitleStyle.Detail]. */
@Composable
fun CompactPaneTitleBand(
    title: String,
    subtitle: String? = null
) {
    CompactHomeTitleBand(
        primaryLine = title,
        secondaryLine = subtitle,
        style = CompactHomeTitleStyle.Detail
    )
}
