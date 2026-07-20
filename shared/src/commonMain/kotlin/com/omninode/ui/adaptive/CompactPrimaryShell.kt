package com.omninode.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.omninode.ui.HomeTab
import com.omninode.ui.OmniBottomBar
import com.omninode.ui.theme.OmniTeal

/** Shared compact home header metrics — keeps Devices, Settings, and explorer bands aligned. */
object CompactHomeChrome {
    val titleBandHorizontalPadding = 20.dp
    val titleBandVerticalPadding = 16.dp
    val eyebrowHeadlineGap = 4.dp
    /** Matches Paired Devices + OmniNode two-line band content height. */
    val titleBandMinHeight = 86.dp
}

/**
 * Compact-mode primary chrome: teal strip (optional exit) + bottom navigation.
 * Screen content supplies the white title band via [CompactHomeTitleBand].
 */
@Composable
fun CompactPrimaryShell(
    selectedTab: HomeTab,
    showExitPower: Boolean,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    onExitApp: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CompactTealStrip(
                showExitPower = showExitPower,
                onExitClick = onExitApp
            )
        },
        bottomBar = {
            OmniBottomBar(
                selected = selectedTab,
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
            content()
        }
    }
}

@Composable
fun CompactTealStrip(
    showExitPower: Boolean,
    onExitClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(OmniTeal)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (showExitPower) {
            IconButton(
                onClick = onExitClick,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = "Exit OmniNode",
                    tint = Color.White
                )
            }
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
fun CompactDevicesTitleBand() {
    CompactHomeTitleBand(
        primaryLine = "Paired Devices",
        secondaryLine = "OmniNode",
        style = CompactHomeTitleStyle.Prominent
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
