package com.fileapex.ui.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopNavPositionPolicy
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.traits
import com.fileapex.i18n.stringRes
import com.fileapex.platform.isDesktopHost
import com.fileapex.presentation.ExplorerSortMode
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.presentation.ExplorerViewModel
import com.fileapex.ui.ExpandableSearchPillRow
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.ExplorerViewModeToggle
import com.fileapex.ui.NavDockPositionToggle
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.isFileApexCleanCurved
import com.fileapex.ui.theme.isFileApexFluentUi
import com.fileapex.ui.theme.isFileApexTiledChrome
import com.fileapex.ui.tools.FavoritesHeaderButton
import com.fileapex.ui.tools.SplitViewHeaderButton
import com.fileapex.ui.tools.ToolsHeaderButton

@Composable
internal fun ExplorerTopBarActions(
    state: ExplorerUiState,
    embeddedInCompactShell: Boolean,
    viewModel: ExplorerViewModel,
    hasExternalRefresh: Boolean = false,
    onOpenTools: (() -> Unit)? = null,
    onOpenFavorites: (() -> Unit)? = null,
    onPickFiles: ((List<String>) -> Unit)? = null
) {
    val jadedOrbital = LocalAppTheme.current.traits.orbitalHome &&
        LocalKineticStyle.current == KineticStyle.JADED_STEEL
    var shareLinkPath by remember { mutableStateOf<String?>(null) }
    shareLinkPath?.let { path ->
        com.fileapex.drop.ui.GuestDropDialog(
            file = java.io.File(path),
            onDismiss = { shareLinkPath = null }
        )
    }
    when {
        state.isSelectionMode -> {
            if (onPickFiles != null) {
                val canSend = (state.contentFiles + state.paneFiles).any { it.id in state.selectedFileIds }
                TextButton(enabled = canSend, onClick = { viewModel.pickSelected(onPickFiles) }) {
                    Text(
                        stringRes("send"),
                        color = if (jadedOrbital) KineticStyleLook.steel else if (embeddedInCompactShell && isFileApexCleanCurved()) Color.White else Color.Unspecified
                    )
                }
            } else if (state.isRemoteTarget) {
                TextButton(
                    onClick = viewModel::downloadSelected,
                    enabled = state.canDownloadSelection && !state.isDownloading
                ) {
                    Text(
                        if (state.isDownloading) "…" else stringRes("download"),
                        color = if (jadedOrbital) KineticStyleLook.steel else if (embeddedInCompactShell && isFileApexCleanCurved()) Color.White else Color.Unspecified
                    )
                }
            } else {
                val singleLocalFile = (state.contentFiles + state.paneFiles)
                    .filter { it.id in state.selectedFileIds }
                    .singleOrNull()
                    ?.takeIf { !it.isDirectory }
                if (singleLocalFile != null) {
                    TextButton(onClick = { shareLinkPath = singleLocalFile.absolutePath }) {
                        Text(
                            stringRes("share_link_page_title"),
                            color = if (jadedOrbital) KineticStyleLook.steel else if (embeddedInCompactShell && isFileApexCleanCurved()) Color.White else Color.Unspecified
                        )
                    }
                }
            }
            if (embeddedInCompactShell && DesktopNavPositionPolicy.showHeaderToggle(com.fileapex.data.settings.LocalAppTheme.current, isDesktopHost())) {
                NavDockPositionToggle()
            }
        }
        else -> {
            if (!hasExternalRefresh) {
                TextButton(onClick = { viewModel.enterSelectionMode() }) {
                    Text(
                        text = stringRes("select"),
                        color = if (jadedOrbital) KineticStyleLook.steel else if (embeddedInCompactShell && isFileApexCleanCurved()) Color.White else Color.Unspecified
                    )
                }
                if (state.canPaste) {
                    TextButton(onClick = viewModel::pasteHere) {
                        Text(
                            text = stringRes("paste"),
                            color = if (jadedOrbital) KineticStyleLook.steel else if (embeddedInCompactShell && isFileApexCleanCurved()) Color.White else Color.Unspecified
                        )
                    }
                }
            }
            if (onOpenFavorites != null) {
                FavoritesHeaderButton(
                    onClick = onOpenFavorites,
                    tint = if (jadedOrbital) KineticStyleLook.steel else MaterialTheme.colorScheme.primary
                )
            }
            if (onOpenTools != null) {
                ToolsHeaderButton(
                    onClick = onOpenTools,
                    tint = if (jadedOrbital) KineticStyleLook.steel else MaterialTheme.colorScheme.primary
                )
            }
            if (embeddedInCompactShell) {
                val splitAllowed = onPickFiles == null
                if (splitAllowed) {
                    val splitOn = state.viewMode == ExplorerViewMode.Split
                    SplitViewHeaderButton(
                        active = splitOn,
                        tint = if (jadedOrbital) KineticStyleLook.steel else if (isFileApexCleanCurved()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = {
                            if (splitOn) {
                                viewModel.toggleViewMode(includeSplit = false)
                            } else {
                                com.fileapex.di.FileApexServices.settings.setExplorerViewMode(ExplorerViewMode.Split)
                            }
                        }
                    )
                }
                ExplorerViewModeToggle(
                    viewMode = state.viewMode,
                    onToggle = { viewModel.toggleViewMode(includeSplit = false) },
                    includeSplit = false
                )
                if (DesktopNavPositionPolicy.showHeaderToggle(com.fileapex.data.settings.LocalAppTheme.current, isDesktopHost()) && splitAllowed) {
                    NavDockPositionToggle()
                }
            }
            if (!hasExternalRefresh) {
                if (isFileApexTiledChrome()) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(enabled = !state.isRefreshing, onClick = viewModel::refresh),
                        contentAlignment = Alignment.Center
                    ) {
                        JadedRaisedTile(tileSize = 28.dp) {
                            if (state.isRefreshing) {
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
                        onClick = viewModel::refresh,
                        enabled = !state.isRefreshing
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = explorerIcon(ExplorerIcon.Refresh),
                                contentDescription = stringRes("refresh"),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
internal fun ExplorerFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sortMode: ExplorerSortMode,
    onSortModeChange: (ExplorerSortMode) -> Unit,
    leading: (@Composable (modifier: Modifier, compact: Boolean) -> Unit)? = null,
    searchPillModifier: Modifier = Modifier
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val currentTheme = LocalAppTheme.current
        val jadedField = currentTheme.traits.orbitalHome &&
            LocalKineticStyle.current == KineticStyle.JADED_STEEL
        val curvedField = currentTheme.let { it == AppTheme.SIMPLE || it == AppTheme.CLEAN || it == AppTheme.FLUX_GLASS || it == AppTheme.CAROUSEL } && !isFileApexFluentUi()
        if (ExplorerFilterBarPolicy.usesExpandableSearch(currentTheme, hasLeading = leading != null)) {
            ExpandableSearchPillRow(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = stringRes(ExplorerFilterLabelPolicy.placeholderKey()),
                leading = leading ?: { _, _ -> },
                collapseLeadingCompletely = leading == null,
                useJadedStyle = jadedField && leading == null,
                modifier = Modifier.weight(1f),
                searchPillModifier = searchPillModifier
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringRes(ExplorerFilterLabelPolicy.placeholderKey()), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringRes("clear"))
                        }
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = if (curvedField) RoundedCornerShape(50) else OutlinedTextFieldDefaults.shape
            )
        }
        Box {
            IconButton(onClick = { sortMenuOpen = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = stringRes("sort_by"),
                    tint = when {
                        jadedField -> KineticStyleLook.steel
                        currentTheme.traits.glassChrome -> Color.White
                        else -> LocalContentColor.current
                    }
                )
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                ExplorerSortMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ExplorerSortMode.Name -> stringRes("sort_name")
                        ExplorerSortMode.Date -> stringRes("sort_date")
                        ExplorerSortMode.Size -> stringRes("sort_size")
                    }
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = {
                            if (mode == sortMode) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                        onClick = {
                            onSortModeChange(mode)
                            sortMenuOpen = false
                        }
                    )
                }
            }
        }
    }
}

object ExplorerFilterBarPolicy {
    fun usesExpandableSearch(theme: AppTheme, hasLeading: Boolean): Boolean =
        hasLeading || theme.traits.orbitalHome
}

object ExplorerFilterLabelPolicy {
    fun placeholderKey(): String = "filter"
}
