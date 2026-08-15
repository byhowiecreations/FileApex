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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.ui.HomeTab
import com.fileapex.ui.NoteHeaderButton
import com.fileapex.ui.QueuedFilesButton
import com.fileapex.ui.FileApexBottomBar
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.fileApexChromeBottomEdge
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeContentColor

import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.GridView
import androidx.compose.ui.text.style.TextAlign

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
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme == AppTheme.FLUX_GLASS || currentTheme == AppTheme.KINETIC_SPHERE
    Scaffold(
        containerColor = if (isCustomGlass) Color.Transparent else MaterialTheme.colorScheme.background,
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
            // Omit separate top teal strip so Default theme matches Flux Glass unified header structure.
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
    // Disabled to unify header band across themes.
    return
}

/** Layout style for [CompactHomeTitleBand]. */
enum class CompactHomeTitleStyle {
    /** Small eyebrow line + large headline (Devices / Settings root). */
    Prominent,
    /** Medium title + optional detail subtitle (file explorer). */
    Detail
}

@Composable
fun FluxGlassHeader(
    primaryTitle: String = "FileApex",
    secondaryTitle: String? = "Paired Devices",
    showLayoutView: Boolean = false,
    onToggleLayoutView: (() -> Unit)? = null,
    showCloseService: Boolean = false,
    onCloseService: (() -> Unit)? = null,
    onOpenNotes: (() -> Unit)? = null,
    onOpenTransferQueue: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme == AppTheme.FLUX_GLASS || currentTheme == AppTheme.KINETIC_SPHERE
    val titleColor = if (isCustomGlass) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (isCustomGlass) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant
    val accentTint = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCustomGlass) Color.Transparent else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primaryTitle,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    letterSpacing = (-0.5).sp
                ),
                color = titleColor
            )
            if (!secondaryTitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = secondaryTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp
                    ),
                    color = subtitleColor
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (onOpenTransferQueue != null) {
                QueuedFilesButton(onClick = onOpenTransferQueue)
            }

            if (onOpenNotes != null) {
                NoteHeaderButton(onOpenNotes = onOpenNotes)
            }

            actions()

            if (showLayoutView && onToggleLayoutView != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable(onClick = onToggleLayoutView)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.GridView,
                        contentDescription = "Layout View",
                        tint = accentTint,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Layout\nView",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            textAlign = TextAlign.Center
                        ),
                        color = titleColor.copy(alpha = 0.85f)
                    )
                }
            }

            if (showCloseService && onCloseService != null) {
                Surface(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCloseService),
                    shape = CircleShape,
                    color = Color(0x3300E676),
                    border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.70f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = "Exit FileApex",
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompactDevicesTitleBand(
    actions: @Composable RowScope.() -> Unit = {},
    showLayoutView: Boolean = false,
    onToggleLayoutView: (() -> Unit)? = null,
    showCloseService: Boolean = false,
    onCloseService: (() -> Unit)? = null,
    onOpenNotes: (() -> Unit)? = null,
    onOpenTransferQueue: (() -> Unit)? = null
) {
    val currentTheme = LocalAppTheme.current
    val allowLayoutView = showLayoutView && currentTheme != AppTheme.KINETIC_SPHERE
    FluxGlassHeader(
        primaryTitle = "FileApex",
        secondaryTitle = "Paired Devices",
        showLayoutView = allowLayoutView,
        onToggleLayoutView = if (allowLayoutView) onToggleLayoutView else null,
        showCloseService = showCloseService,
        onCloseService = onCloseService,
        onOpenNotes = onOpenNotes,
        onOpenTransferQueue = onOpenTransferQueue,
        actions = actions
    )
}

@Composable
fun CompactHomeTitleBand(
    primaryLine: String,
    secondaryLine: String? = null,
    style: CompactHomeTitleStyle = CompactHomeTitleStyle.Detail,
    modifier: Modifier = Modifier,
    onOpenTransferQueue: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme == AppTheme.FLUX_GLASS || currentTheme == AppTheme.KINETIC_SPHERE
    if (isCustomGlass && style == CompactHomeTitleStyle.Prominent) {
        FluxGlassHeader(
            primaryTitle = "FileApex",
            secondaryTitle = if (primaryLine == "FileApex") secondaryLine ?: "Paired Devices" else primaryLine,
            onOpenTransferQueue = onOpenTransferQueue,
            actions = actions
        )
    } else {
        CompactHomeTitleBandRow(
            modifier = modifier,
            onOpenTransferQueue = onOpenTransferQueue,
            actions = actions
        ) {
            when (style) {
                CompactHomeTitleStyle.Prominent -> {
                    Text(
                        text = "FileApex",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (isCustomGlass) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(CompactHomeChrome.eyebrowHeadlineGap))
                    Text(
                        text = if (primaryLine == "FileApex") secondaryLine ?: "Paired Devices" else primaryLine,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = if (isCustomGlass) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                CompactHomeTitleStyle.Detail -> {
                    Text(
                        text = primaryLine,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCustomGlass) Color.White else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!secondaryLine.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(CompactHomeChrome.eyebrowHeadlineGap))
                        Text(
                            text = secondaryLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCustomGlass) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

            }
        }
    }
}

@Composable
private fun CompactHomeTitleBandRow(
    modifier: Modifier = Modifier,
    onOpenTransferQueue: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: @Composable () -> Unit
) {
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CompactHomeChrome.titleBandMinHeight)
            .background(if (isFluxGlass) Color.Transparent else MaterialTheme.colorScheme.surface)
            .padding(
                horizontal = CompactHomeChrome.titleBandHorizontalPadding,
                vertical = CompactHomeChrome.titleBandVerticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            titleContent()
        }
        if (onOpenTransferQueue != null) {
            QueuedFilesButton(onClick = onOpenTransferQueue)
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
