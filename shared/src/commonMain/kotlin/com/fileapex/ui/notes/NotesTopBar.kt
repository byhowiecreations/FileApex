package com.fileapex.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BulletinFilter
import com.fileapex.presentation.BulletinSearchPolicy
import com.fileapex.ui.DesktopHoverTooltip
import com.fileapex.ui.theme.FileApexTeal

@Composable
internal fun NotesScope.NotesTopBar() {
    with(frame) { with(live) {
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringRes("back"),
                    tint = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringRes("bulletin_board"),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = textColor
                )
                Text(
                    text = stringRes("notes_subtitle"),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = subTextColor
                )
            }
            DesktopHoverTooltip(stringRes("search_notes")) {
                IconButton(
                    modifier = Modifier.onGloballyPositioned { coords ->
                        searchToggleRect = overlayCoords?.localBoundingBoxOf(coords, clipBounds = false)
                    },
                    onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) viewModel.setSearchQuery("")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringRes("search_notes"),
                        tint = if (searchOpen || searchQuery.isNotBlank()) {
                            if (isCustomGlass) Color(0xFF00E5FF) else FileApexTeal
                        } else {
                            subTextColor
                        }
                    )
                }
            }
            Box {
                IconButton(onClick = { filterMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = stringRes("filter_notes"),
                        tint = if (activeFilter != BulletinFilter.All) {
                            if (isCustomGlass) Color(0xFF00E5FF) else FileApexTeal
                        } else {
                            subTextColor
                        }
                    )
                }
                NotesFilterDropdownMenu()
            }
        }
        if (BulletinSearchPolicy.shouldShowSearchField(searchOpen)) {
            DisposableEffect(Unit) {
                onDispose { searchAreaRect = null }
            }
            NotesSearchField(
                query = searchQuery,
                subTextColor = subTextColor,
                onQueryChange = { viewModel.setSearchQuery(it) },
                onClearOrClose = {
                    if (searchQuery.isNotBlank()) viewModel.setSearchQuery("") else searchOpen = false
                },
                onDismiss = { searchOpen = false },
                onPositionedInOverlay = { coords ->
                    searchAreaRect = overlayCoords?.localBoundingBoxOf(coords, clipBounds = false)
                }
            )
        } else if (BulletinSearchPolicy.shouldShowActiveQueryChip(searchOpen, searchQuery)) {
            ActiveSearchQueryBar(
                query = searchQuery,
                subTextColor = subTextColor,
                onReopen = { searchOpen = true },
                onClear = { viewModel.setSearchQuery("") }
            )
        }
        }
    } }
}

@Composable
private fun NotesScope.NotesFilterDropdownMenu() {
    with(frame) { with(live) {
        val checkTint = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
        DropdownMenu(
            expanded = filterMenuExpanded,
            onDismissRequest = { filterMenuExpanded = false }
        ) {
            FilterMenuItem("filter_all", activeFilter == BulletinFilter.All, checkTint) {
                viewModel.setFilter(BulletinFilter.All)
                filterMenuExpanded = false
            }
            if (hasLinks) FilterMenuItem("filter_links", activeFilter == BulletinFilter.Links, checkTint) {
                viewModel.setFilter(BulletinFilter.Links)
                filterMenuExpanded = false
            }
            if (hasImages) FilterMenuItem("filter_images", activeFilter == BulletinFilter.Images, checkTint) {
                viewModel.setFilter(BulletinFilter.Images)
                filterMenuExpanded = false
            }
            if (hasDocs) FilterMenuItem("filter_documents", activeFilter == BulletinFilter.Documents, checkTint) {
                viewModel.setFilter(BulletinFilter.Documents)
                filterMenuExpanded = false
            }
            if (hasSnippets) FilterMenuItem("filter_snippets", activeFilter == BulletinFilter.Snippets, checkTint) {
                viewModel.setFilter(BulletinFilter.Snippets)
                filterMenuExpanded = false
            }
            if (hasOtherFiles) FilterMenuItem("filter_files", activeFilter == BulletinFilter.Files, checkTint) {
                viewModel.setFilter(BulletinFilter.Files)
                filterMenuExpanded = false
            }
            if (hasPinned) FilterMenuItem("filter_pinned", activeFilter == BulletinFilter.Pinned, checkTint) {
                viewModel.setFilter(BulletinFilter.Pinned)
                filterMenuExpanded = false
            }
            if (hasTodo) FilterMenuItem("filter_todo", activeFilter == BulletinFilter.Todo, checkTint) {
                viewModel.setFilter(BulletinFilter.Todo)
                filterMenuExpanded = false
            }
            if (availableTags.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                availableTags.forEach { tag ->
                    val isSelected = activeFilter is BulletinFilter.Tag && (activeFilter as BulletinFilter.Tag).tag == tag
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = tag,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCustomGlass) Color(0xFF00E5FF) else FileApexTeal
                            )
                        },
                        trailingIcon = if (isSelected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint) }
                        } else null,
                        onClick = {
                            viewModel.setFilter(BulletinFilter.Tag(tag))
                            filterMenuExpanded = false
                        }
                    )
                }
            }
        }
    } }
}

@Composable
private fun NotesSearchField(
    query: String,
    subTextColor: Color,
    onQueryChange: (String) -> Unit,
    onClearOrClose: () -> Unit,
    onDismiss: () -> Unit,
    onPositionedInOverlay: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringRes("search_notes_hint")) },
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = subTextColor
            )
        },
        trailingIcon = {
            IconButton(onClick = onClearOrClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringRes("clear_search"),
                    modifier = Modifier.size(18.dp),
                    tint = subTextColor
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .onGloballyPositioned(onPositionedInOverlay)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    focusManager.clearFocus()
                    onDismiss()
                    true
                } else {
                    false
                }
            }
    )
}

@Composable
private fun ActiveSearchQueryBar(
    query: String,
    subTextColor: Color,
    onReopen: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onReopen,
            shape = RoundedCornerShape(50),
            color = subTextColor.copy(alpha = 0.12f)
        ) {
            Row(
                modifier = Modifier.padding(PaddingValues(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(14.dp), tint = subTextColor)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = query.trim(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringRes("clear_search"), modifier = Modifier.size(14.dp), tint = subTextColor)
                }
            }
        }
    }
}

@Composable
private fun FilterMenuItem(labelKey: String, selected: Boolean, checkTint: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = stringRes(labelKey), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint) }
        } else null,
        onClick = onClick
    )
}
