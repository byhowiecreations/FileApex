package com.fileapex.ui.adaptive

import com.fileapex.ui.theme.fileApexHeaderSubtitleStyle
import com.fileapex.ui.theme.fileApexHeaderTitleStyle
import androidx.compose.runtime.getValue
import com.fileapex.ui.HeaderIconButton
import androidx.compose.foundation.layout.width
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.ExplorerIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.ui.ExpandableSearchPillRow
import com.fileapex.ui.NoteHeaderButton
import com.fileapex.ui.NoteIconKind
import com.fileapex.ui.QueuedFilesButton
import com.fileapex.ui.theme.fileApexAccent
import com.fileapex.ui.theme.fileApexChromeBottomEdge
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.fileApexFloatingChrome
import com.fileapex.ui.theme.isFileApexCleanCurved
import com.fileapex.ui.theme.isFileApexCleanModern
import com.fileapex.ui.theme.fileApexHeaderActionTint
import com.fileapex.ui.theme.isFileApexJadedSteel
import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.di.FileApexServices
import androidx.compose.material.icons.filled.GridView
import com.fileapex.ui.FileApexIcons
import androidx.compose.runtime.collectAsState
import com.fileapex.data.settings.DesktopNavPositionPolicy
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.platform.isDesktopHost
import com.fileapex.ui.NavDockPositionToggle

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
                        style = fileApexHeaderTitleStyle(),
                        color = titleColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (resolvedSecondary.isNotBlank() && !cleanModern) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = resolvedSecondary,
                            modifier = Modifier.weight(1f, fill = false),
                            style = fileApexHeaderSubtitleStyle(),
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
                        style = fileApexHeaderTitleStyle(),
                        color = titleColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                    if (!jadedHeader && resolvedSecondary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = resolvedSecondary,
                            style = fileApexHeaderSubtitleStyle(),
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
                    HeaderIconButton(icon, desc, fileApexHeaderActionTint(), onToggleLayoutView)
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
                    cleanBar -> Modifier.fileApexFloatingChrome(fileApexAccent())
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
