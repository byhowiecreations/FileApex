package com.fileapex.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.traits
import com.fileapex.i18n.stringRes
import com.fileapex.platform.isDesktopHost
import com.fileapex.presentation.ExplorerSortMode
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.presentation.ExplorerViewModel
import com.fileapex.ui.DesktopLayoutToggle
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.ExplorerViewModeToggle
import com.fileapex.ui.tools.ToolsHeaderButton
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.LocalJadedHazeState
import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.isFileApexCleanCurved
import com.fileapex.ui.theme.isFileApexFluentUi
import com.fileapex.ui.theme.isFileApexTiledChrome
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

@Composable
internal fun ExplorerTopBarActions(
    state: ExplorerUiState,
    embeddedInCompactShell: Boolean,
    viewModel: ExplorerViewModel,
    hasExternalRefresh: Boolean = false,
    onOpenTools: (() -> Unit)? = null,
    onPickFiles: ((List<String>) -> Unit)? = null
) {
    val jadedOrbital = LocalAppTheme.current.traits.orbitalHome &&
        LocalKineticStyle.current == KineticStyle.JADED_STEEL
    when {
        state.isSelectionMode -> {
            if (onPickFiles != null) {
                val chosen = viewModel.selectedItems().filter { !it.isDirectory }.map { it.absolutePath }
                TextButton(enabled = chosen.isNotEmpty(), onClick = { onPickFiles(chosen) }) {
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
            }
            if (embeddedInCompactShell && isDesktopHost()) {
                DesktopLayoutToggle()
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
            if (onOpenTools != null) {
                ToolsHeaderButton(
                    onClick = onOpenTools,
                    tint = if (jadedOrbital) KineticStyleLook.steel else MaterialTheme.colorScheme.primary
                )
            }
            if (embeddedInCompactShell) {
                val splitAllowed = onPickFiles == null
                ExplorerViewModeToggle(
                    viewMode = state.viewMode,
                    onToggle = { viewModel.toggleViewMode(splitAllowed) },
                    includeSplit = splitAllowed
                )
                if (isDesktopHost() && splitAllowed) {
                    DesktopLayoutToggle()
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

private val FilterRowHeight = 44.dp

/**
 * The leading slot (a drop area) and the filter share one row. The filter is a search icon until
 * tapped, then the two slide past each other; tapping the shrunken slot closes the filter again.
 */
@Composable
private fun SlidingFilterWithLeading(
    query: String,
    onQueryChange: (String) -> Unit,
    leading: @Composable (modifier: Modifier, compact: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    var wasFocused by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val showField = open || query.isNotEmpty()
    val fraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (showField) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 260)
    )
    LaunchedEffect(open) {
        if (open) {
            androidx.compose.runtime.withFrameNanos { }
            focus.requestFocus()
        }
    }
    val pill = RoundedCornerShape(50)
    BoxWithConstraints(modifier = modifier.height(FilterRowHeight)) {
        val gap = 8.dp
        val filterWidth = androidx.compose.ui.unit.lerp(FilterRowHeight, maxWidth - FilterRowHeight - gap, fraction)
        val leadingWidth = maxWidth - gap - filterWidth
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(leadingWidth)
                    .height(FilterRowHeight)
                    .clip(pill)
                    .clickable(enabled = showField) {
                        onQueryChange("")
                        wasFocused = false
                        open = false
                    }
            ) {
                leading(Modifier.fillMaxSize(), leadingWidth < 170.dp)
            }
            Spacer(modifier = Modifier.width(gap))
            Row(
                modifier = Modifier
                    .width(filterWidth)
                    .height(FilterRowHeight)
                    .clip(pill)
                    .border(
                        width = if (showField) 2.dp else 1.dp,
                        color = if (showField) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = pill
                    )
                    .clickable(enabled = !showField) { open = true }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringRes("filter_this_folder"),
                    modifier = Modifier.size(22.dp)
                )
                if (showField) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    wasFocused = true
                                } else if (wasFocused && query.isEmpty()) {
                                    wasFocused = false
                                    open = false
                                }
                            },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        stringRes("filter_this_folder"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringRes("clear"), modifier = Modifier.size(18.dp))
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
    leading: (@Composable (modifier: Modifier, compact: Boolean) -> Unit)? = null
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val jadedField = LocalAppTheme.current.traits.orbitalHome &&
            LocalKineticStyle.current == KineticStyle.JADED_STEEL
        val curvedField = LocalAppTheme.current.let { it == AppTheme.SIMPLE || it == AppTheme.CLEAN || it == AppTheme.FLUX_GLASS || it == AppTheme.CAROUSEL } && !isFileApexFluentUi()
        if (leading != null) {
            SlidingFilterWithLeading(
                query = query,
                onQueryChange = onQueryChange,
                leading = leading,
                modifier = Modifier.weight(1f)
            )
        } else if (jadedField) {
            val pill = RoundedCornerShape(50)
            val ink = Color(0xFFE4EEEF)
            val hint = Color(0xFFB7C4C6)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
                cursorBrush = SolidColor(ink),
                modifier = Modifier.weight(1f).height(44.dp),
                decorationBox = { inner ->
                    val haze = LocalJadedHazeState.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(pill)
                            .then(
                                if (haze != null) {
                                    Modifier.hazeEffect(
                                        state = haze,
                                        style = HazeStyle(
                                            backgroundColor = Color.Transparent,
                                            tints = listOf(HazeTint(Color.White.copy(alpha = 0.09f))),
                                            blurRadius = 24.dp,
                                            noiseFactor = 0.04f
                                        )
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .background(Color.White.copy(alpha = 0.09f))
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    0f to Color.White.copy(alpha = 0.28f),
                                    1f to Color.White.copy(alpha = 0.10f)
                                ),
                                shape = pill
                            )
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = hint)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringRes("filter_this_folder"),
                                    color = hint,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            inner()
                        }
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringRes("clear"), tint = hint)
                            }
                        }
                    }
                }
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringRes("filter_this_folder")) },
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
                        LocalAppTheme.current.traits.glassChrome -> Color.White
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
