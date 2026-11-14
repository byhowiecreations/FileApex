package com.fileapex.ui.adaptive

import androidx.compose.foundation.layout.width
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.ExplorerIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.sp
import com.fileapex.i18n.stringRes
import com.fileapex.ui.DesktopHoverTooltip
import com.fileapex.ui.ExpandableSearchPillRow
import com.fileapex.ui.HomeTab
import com.fileapex.ui.NoteHeaderButton
import com.fileapex.ui.NoteIconKind
import com.fileapex.ui.QueuedFilesButton
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.fileApexChromeBottomEdge
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.fileApexFloatingChrome
import com.fileapex.ui.theme.isFileApexCleanCurved
import com.fileapex.ui.theme.isFileApexCleanModern
import com.fileapex.ui.theme.isFileApexTiledChrome
import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.fileApexHeaderActionTint
import com.fileapex.ui.theme.isFileApexJadedSteel

import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.di.FileApexServices
import androidx.compose.material.icons.filled.GridView
import com.fileapex.ui.FileApexIcons
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fileapex.data.settings.DesktopNavPosition
import com.fileapex.data.settings.DesktopNavPositionPolicy
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import androidx.compose.foundation.clickable
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
    onMainHomeScreen: Boolean = true,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    onExitApp: () -> Unit,
    tealStripActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    ThemeCompactNavScaffold(
        selectedTab = selectedTab,
        onMainHomeScreen = onMainHomeScreen,
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
fun FluxGlassHeader(
    primaryTitle: String = "FileApex",
    secondaryTitle: String? = null,
    showLayoutView: Boolean = false,
    onToggleLayoutView: (() -> Unit)? = null,
    showDesktopLayoutToggle: Boolean = false,
    showCloseService: Boolean = false,
    onCloseService: (() -> Unit)? = null,
    onOpenNotes: (() -> Unit)? = null,
    onOpenTransferQueue: (() -> Unit)? = null,
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = "",
    searchButtonModifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme.traits.glassChrome
    val cleanBar = isFileApexCleanCurved()
    val cleanModern = isFileApexCleanModern()
    val titleColor = if (isCustomGlass || cleanBar) Color.White else fileApexChromeContentColor()
    val subtitleColor = if (isCustomGlass || cleanBar) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    val resolvedSecondary = secondaryTitle ?: stringRes("paired_devices_title")

    val headerBg = when {
        currentTheme.traits.canvasHome -> Color.Black
        isCustomGlass -> Color.Transparent
        cleanModern -> fileApexChromeContainerColor()
        else -> MaterialTheme.colorScheme.surface
    }
    val jadedHeader = isFileApexJadedSteel()

    val headerBody: @Composable (Modifier) -> Unit = { bodyModifier ->
        Row(
            modifier = bodyModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (cleanBar || cleanModern) {
                Row(
                    modifier = Modifier.weight(1f, fill = true),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (cleanModern) {
                        WideTopBarBrandMark()
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = primaryTitle,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = titleColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (resolvedSecondary.isNotBlank() && !cleanModern) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = resolvedSecondary,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = subtitleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.weight(1f, fill = true)) {
                    Text(
                        text = primaryTitle,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (jadedHeader) 22.sp else 30.sp,
                            letterSpacing = (-0.5).sp
                        ),
                        color = titleColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                    if (!jadedHeader && resolvedSecondary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = resolvedSecondary,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp
                            ),
                            color = subtitleColor,
                            maxLines = 2,
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val headerIconTint = fileApexHeaderActionTint()
                if (onOpenTransferQueue != null) {
                    QueuedFilesButton(onClick = onOpenTransferQueue, iconTint = headerIconTint)
                }

                if (onOpenNotes != null) {
                    val noteIconKind = when {
                        isFileApexCleanModern() -> NoteIconKind.BLACK
                        currentTheme.traits.fluxSurfaces -> NoteIconKind.WHITE
                        isFileApexJadedSteel() || currentTheme.traits.glassChrome -> NoteIconKind.GREEN
                        else -> NoteIconKind.BLACK
                    }
                    NoteHeaderButton(onOpenNotes = onOpenNotes, iconKind = noteIconKind)
                }

                if (showLayoutView && onToggleLayoutView != null) {
                    val freestyleMode by FileApexServices.settings.freestyleLayoutMode.collectAsState()
                    val icon = if (currentTheme.traits.canvasHome) {
                        when (freestyleMode) {
                            FreestyleLayoutMode.CARDS_VERTICAL -> explorerIcon(ExplorerIcon.CardsVertical)
                            FreestyleLayoutMode.CARDS_HORIZONTAL -> explorerIcon(ExplorerIcon.CardsHorizontal)
                            FreestyleLayoutMode.TILES -> FileApexIcons.Atr
                        }
                    } else {
                        explorerIcon(ExplorerIcon.GridView)
                    }
                    val desc = if (currentTheme.traits.canvasHome) {
                        when (freestyleMode) {
                            FreestyleLayoutMode.CARDS_VERTICAL -> "Vertical Cards Layout"
                            FreestyleLayoutMode.CARDS_HORIZONTAL -> "Horizontal Cards Layout"
                            FreestyleLayoutMode.TILES -> "Tiles Layout"
                        }
                    } else {
                        stringRes("layout_view")
                    }
                    val layoutIconTint = fileApexHeaderActionTint()
                    if (isFileApexTiledChrome()) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clickable(onClick = onToggleLayoutView),
                            contentAlignment = Alignment.Center
                        ) {
                            JadedRaisedTile(tileSize = 28.dp) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = desc,
                                    tint = fileApexTileTint(),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else {
                        IconButton(
                            onClick = onToggleLayoutView,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = desc,
                                tint = layoutIconTint,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                if (showDesktopLayoutToggle && DesktopNavPositionPolicy.showHeaderToggle(currentTheme, isDesktopHost())) {
                    NavDockPositionToggle(
                        modifier = Modifier.size(40.dp),
                        iconTint = headerIconTint
                    )
                }

                actions()

                if (showCloseService && onCloseService != null) {
                    FileApexPowerButton(onClick = onCloseService)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    jadedHeader -> Modifier.jadedCompactHeaderPanel()
                    cleanBar -> Modifier.fileApexFloatingChrome(FileApexTeal)
                    else -> Modifier.background(headerBg).fileApexChromeBottomEdge()
                }
            )
            .defaultMinSize(minHeight = if (cleanBar || cleanModern) 52.dp else Dp.Unspecified)
            .padding(horizontal = if (jadedHeader) 14.dp else if (cleanBar || cleanModern) 12.dp else 16.dp, vertical = if (jadedHeader) 4.dp else if (cleanBar || cleanModern) 6.dp else 12.dp)
    ) {
        if (onSearchQueryChange != null) {
            ExpandableSearchPillRow(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = searchPlaceholder,
                collapseLeadingCompletely = true,
                constrainHeight = false,
                useJadedStyle = jadedHeader,
                useLightChrome = (isCustomGlass || cleanBar) && !jadedHeader,
                modifier = Modifier.fillMaxWidth(),
                searchPillModifier = searchButtonModifier,
                leading = { leadingModifier, _ -> headerBody(leadingModifier) }
            )
        } else {
            headerBody(Modifier.fillMaxWidth())
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
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
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
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = if (lightText) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = true,
                        maxLines = 2
                    )
                }

                CompactHomeTitleStyle.Detail -> {
                    Text(
                        text = primaryLine,
                        style = if (isFileApexCleanCurved()) {
                            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        } else if (jadedHeader) {
                            MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                letterSpacing = (-0.5).sp
                            )
                        } else {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        },
                        color = if (lightText) Color.White else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!secondaryLine.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(if (jadedHeader) 1.dp else CompactHomeChrome.eyebrowHeadlineGap))
                        Text(
                            text = secondaryLine,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
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
            Column(modifier = Modifier.weight(1f)) {
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
                    isFileApexCleanCurved() -> Modifier.fileApexFloatingChrome(FileApexTeal)
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
private fun Modifier.jadedCompactHeaderPanel(): Modifier {
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
