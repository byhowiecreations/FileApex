package com.fileapex.ui.adaptive

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
import com.fileapex.ui.HomeTab
import com.fileapex.ui.NoteHeaderButton
import com.fileapex.ui.NoteIconKind
import com.fileapex.ui.QueuedFilesButton
import com.fileapex.ui.FileApexBottomBar
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.fileApexChromeBottomEdge
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.fileApexHeaderActionTint
import com.fileapex.ui.theme.isFileApexJadedSteel

import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.di.FileApexServices
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.TableRows
import com.fileapex.ui.FileApexIcons
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.GridView
import com.fileapex.platform.isDesktopHost
import com.fileapex.ui.DesktopLayoutToggle

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
    val isCustomGlass = currentTheme.traits.glassChrome
    Scaffold(
        containerColor = if (isCustomGlass) Color.Transparent else MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!currentTheme.traits.canvasHome) {
                FileApexBottomBar(
                    selected = selectedTab,
                    onMainHomeScreen = onMainHomeScreen,
                    onDevices = onDevices,
                    onFiles = onFiles,
                    onSettings = onSettings
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (currentTheme.traits.canvasHome) Modifier else Modifier.padding(padding))
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
    showCloseService: Boolean = false,
    onCloseService: (() -> Unit)? = null,
    onOpenNotes: (() -> Unit)? = null,
    onOpenTransferQueue: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme.traits.glassChrome
    val titleColor = if (isCustomGlass) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (isCustomGlass) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant
    val accentTint = if (isCustomGlass) FluxGlassPalette.accent else FileApexTeal
    val resolvedSecondary = secondaryTitle ?: stringRes("paired_devices_title")

    val headerBg = if (currentTheme.traits.canvasHome) Color.Black else if (isCustomGlass) Color.Transparent else MaterialTheme.colorScheme.surface
    val jadedHeader = isFileApexJadedSteel()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (jadedHeader) Modifier.jadedCompactHeaderPanel() else Modifier.background(headerBg))
            .padding(horizontal = if (jadedHeader) 14.dp else 16.dp, vertical = if (jadedHeader) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val headerIconTint = fileApexHeaderActionTint()
            if (onOpenTransferQueue != null) {
                QueuedFilesButton(onClick = onOpenTransferQueue, iconTint = headerIconTint)
            }

            if (onOpenNotes != null) {
                val noteIconKind = if (isFileApexJadedSteel() || currentTheme.traits.glassChrome) {
                    NoteIconKind.GREEN
                } else {
                    NoteIconKind.BLACK
                }
                NoteHeaderButton(onOpenNotes = onOpenNotes, iconKind = noteIconKind)
            }

            if (showLayoutView && onToggleLayoutView != null) {
                val freestyleMode by FileApexServices.settings.freestyleLayoutMode.collectAsState()
                val icon = if (currentTheme.traits.canvasHome) {
                    when (freestyleMode) {
                        FreestyleLayoutMode.CARDS_VERTICAL -> Icons.Filled.TableRows
                        FreestyleLayoutMode.CARDS_HORIZONTAL -> Icons.Filled.ViewColumn
                        FreestyleLayoutMode.TILES -> FileApexIcons.Atr
                    }
                } else {
                    Icons.Filled.GridView
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
                if (isDesktopHost()) {
                    DesktopLayoutToggle(
                        modifier = Modifier.size(40.dp),
                        iconTint = layoutIconTint
                    )
                }
            }

            actions()

            if (showCloseService && onCloseService != null) {
                FileApexPowerButton(onClick = onCloseService)
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
    val allowLayoutView = showLayoutView && !currentTheme.traits.orbitalHome
    FluxGlassHeader(
        primaryTitle = "FileApex",
        secondaryTitle = stringRes("paired_devices_title"),
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
    val isCustomGlass = currentTheme.traits.glassChrome
    if (isCustomGlass && style == CompactHomeTitleStyle.Prominent) {
        FluxGlassHeader(
            primaryTitle = "FileApex",
            secondaryTitle = if (primaryLine == "FileApex") {
                secondaryLine ?: stringRes("paired_devices_title")
            } else {
                primaryLine
            },
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
                        text = if (primaryLine == "FileApex") {
                            secondaryLine ?: stringRes("paired_devices_title")
                        } else {
                            primaryLine
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = if (isCustomGlass) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = true,
                        maxLines = 2
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
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme.traits.glassChrome
    val headerBg = if (currentTheme.traits.canvasHome) Color.Black else if (isCustomGlass) Color.Transparent else MaterialTheme.colorScheme.surface
    val jadedHeader = isFileApexJadedSteel()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (jadedHeader) Modifier.jadedCompactHeaderPanel() else Modifier.background(headerBg))
            .defaultMinSize(minHeight = CompactHomeChrome.titleBandMinHeight)
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
            QueuedFilesButton(
                onClick = onOpenTransferQueue,
                iconTint = fileApexHeaderActionTint()
            )
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

@Composable
internal fun JadedRaisedTile(
    tileSize: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val tile = RoundedCornerShape(8.dp)
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
    val jaded = isFileApexJadedSteel()
    val accent = if (jaded) KineticStyleLook.steel else Color(0xFF00E676)
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp)
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = if (jaded) Color(0xCC101820) else accent.copy(alpha = 0.20f),
            border = BorderStroke(1.dp, accent.copy(alpha = if (jaded) 0.90f else 0.70f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = stringRes("exit_fileapex"),
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

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
