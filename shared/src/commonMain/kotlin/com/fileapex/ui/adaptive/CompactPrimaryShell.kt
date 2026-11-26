package com.fileapex.ui.adaptive

import com.fileapex.ui.theme.fileApexHeaderSubtitleStyle
import com.fileapex.ui.theme.fileApexHeaderTitleStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.ui.ExpandableSearchPillRow
import com.fileapex.ui.HomeTab
import com.fileapex.ui.QueuedFilesButton
import com.fileapex.ui.theme.fileApexAccent
import com.fileapex.ui.theme.fileApexChromeBottomEdge
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexFloatingChrome
import com.fileapex.ui.theme.isFileApexCleanCurved
import com.fileapex.ui.theme.isFileApexCleanModern
import com.fileapex.ui.theme.fileApexHeaderActionTint
import com.fileapex.ui.theme.isFileApexJadedSteel
import com.fileapex.data.settings.DesktopNavPositionPolicy
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.platform.isDesktopHost
import com.fileapex.ui.NavDockPositionToggle

/** Shared compact home header metrics, keeps Devices, Settings, and explorer bands aligned. */
object CompactHomeChrome {
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
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    onExitApp: () -> Unit,
    tealStripActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    ThemeCompactNavScaffold(
        selectedTab = selectedTab,
        onDevices = onDevices,
        onFiles = onFiles,
        onSettings = onSettings,
        content = content
    )
}

enum class CompactHomeTitleStyle {
    /** Small eyebrow line + large headline (Devices / Settings root). */
    Prominent,
    /** Medium title + optional detail subtitle (file explorer). */
    Detail
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
    val allowLayoutView = showLayoutView && !currentTheme.traits.orbitalHome
    FluxGlassHeader(
        primaryTitle = "FileApex",
        secondaryTitle = stringRes("paired_devices_title"),
        showLayoutView = allowLayoutView,
        onToggleLayoutView = if (allowLayoutView) onToggleLayoutView else null,
        showDesktopLayoutToggle = DesktopNavPositionPolicy.showHeaderToggle(currentTheme, isDesktopHost()),
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
    showNavPositionToggle: Boolean = false,
    showCloseService: Boolean = false,
    onCloseService: (() -> Unit)? = null,
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = "",
    searchButtonModifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme.traits.glassChrome
    val jadedHeader = isFileApexJadedSteel()
    val lightText = isCustomGlass || isFileApexCleanCurved()
    val allowNavToggle = showNavPositionToggle && DesktopNavPositionPolicy.showHeaderToggle(currentTheme, isDesktopHost())
    if (isCustomGlass && style == CompactHomeTitleStyle.Prominent) {
        FluxGlassHeader(
            primaryTitle = "FileApex",
            secondaryTitle = if (primaryLine == "FileApex") {
                secondaryLine ?: stringRes("paired_devices_title")
            } else {
                primaryLine
            },
            showDesktopLayoutToggle = allowNavToggle,
            onOpenTransferQueue = onOpenTransferQueue,
            showCloseService = showCloseService,
            onCloseService = onCloseService,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            searchPlaceholder = searchPlaceholder,
            searchButtonModifier = searchButtonModifier,
            actions = actions
        )
    } else {
        CompactHomeTitleBandRow(
            modifier = modifier,
            onOpenTransferQueue = onOpenTransferQueue,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            searchPlaceholder = searchPlaceholder,
            searchButtonModifier = searchButtonModifier,
            actions = {
                if (allowNavToggle) {
                    NavDockPositionToggle(
                        modifier = Modifier.size(40.dp),
                        iconTint = fileApexHeaderActionTint()
                    )
                }
                actions()
                if (showCloseService && onCloseService != null) {
                    FileApexPowerButton(onClick = onCloseService)
                }
            }
        ) {
            when (style) {
                CompactHomeTitleStyle.Prominent -> {
                    Text(
                        text = "FileApex",
                        style = fileApexHeaderTitleStyle(),
                        color = if (lightText) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(CompactHomeChrome.eyebrowHeadlineGap))
                    Text(
                        text = if (primaryLine == "FileApex") {
                            secondaryLine ?: stringRes("paired_devices_title")
                        } else {
                            primaryLine
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = fileApexHeaderSubtitleStyle(),
                        color = if (lightText) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = true,
                        maxLines = 2
                    )
                }

                CompactHomeTitleStyle.Detail -> {
                    Text(
                        text = primaryLine,
                        style = fileApexHeaderTitleStyle(),
                        color = if (lightText) Color.White else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!secondaryLine.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(if (jadedHeader) 1.dp else CompactHomeChrome.eyebrowHeadlineGap))
                        Text(
                            text = secondaryLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (lightText) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
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
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = "",
    searchButtonModifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: @Composable () -> Unit
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme.traits.glassChrome
    val cleanModern = isFileApexCleanModern()
    val headerBg = when {
        currentTheme.traits.canvasHome -> Color.Black
        isCustomGlass -> Color.Transparent
        cleanModern -> fileApexChromeContainerColor()
        else -> MaterialTheme.colorScheme.surface
    }
    val jadedHeader = isFileApexJadedSteel()
    val rowContent: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).hiddenUnlessItFits()) {
                titleContent()
            }
            if (onOpenTransferQueue != null) {
                QueuedFilesButton(
                    onClick = onOpenTransferQueue,
                    iconTint = fileApexHeaderActionTint()
                )
            }
            actions()
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    jadedHeader -> Modifier.jadedCompactHeaderPanel()
                    isFileApexCleanCurved() -> Modifier.fileApexFloatingChrome(fileApexAccent())
                    else -> Modifier.background(headerBg).fileApexChromeBottomEdge()
                }
            )
            .defaultMinSize(minHeight = if (jadedHeader) Dp.Unspecified else if (isFileApexCleanCurved() || cleanModern) 52.dp else CompactHomeChrome.titleBandMinHeight)
            .padding(
                horizontal = if (jadedHeader) 14.dp else if (isFileApexCleanCurved() || cleanModern) 12.dp else CompactHomeChrome.titleBandHorizontalPadding,
                vertical = if (jadedHeader) 4.dp else if (isFileApexCleanCurved() || cleanModern) 6.dp else CompactHomeChrome.titleBandVerticalPadding
            )
    ) {
        if (onSearchQueryChange != null) {
            ExpandableSearchPillRow(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = searchPlaceholder,
                collapseLeadingCompletely = true,
                constrainHeight = false,
                useJadedStyle = jadedHeader,
                useLightChrome = (isCustomGlass || isFileApexCleanCurved()) && !jadedHeader,
                modifier = Modifier.fillMaxWidth(),
                searchPillModifier = searchButtonModifier,
                leading = { leadingModifier, _ -> rowContent(leadingModifier) }
            )
        } else {
            rowContent(Modifier.fillMaxWidth())
        }
    }
}

/** Places nothing when the content cannot fit whole in the width it is given, so a squeezed title never shows as a fragment. */
internal fun Modifier.hiddenUnlessItFits(): Modifier = layout { measurable, constraints ->
    if (measurable.maxIntrinsicWidth(constraints.maxHeight) > constraints.maxWidth) {
        layout(0, 0) {}
    } else {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
