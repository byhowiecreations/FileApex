package com.fileapex.ui.notes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BulletinFilter
import com.fileapex.ui.EmptyState
import com.fileapex.ui.EmptyStateColors
import com.fileapex.ui.theme.fileApexAccent

/** Which strings the Bulletin Board shows when there is nothing to list. */
internal data class NotesEmptyCopy(
    val titleKey: String,
    val titleArg: String? = null,
    val hintKey: String? = null,
    val actionKey: String? = null
)

internal fun notesEmptyCopy(isSearchEmpty: Boolean, isFilteredEmpty: Boolean, filter: BulletinFilter): NotesEmptyCopy = when {
    isSearchEmpty -> NotesEmptyCopy("no_matching_notes", actionKey = "clear_search")
    isFilteredEmpty -> when (filter) {
        BulletinFilter.Links -> NotesEmptyCopy("no_filtered_links", actionKey = "filter_all")
        BulletinFilter.Images -> NotesEmptyCopy("no_filtered_images", actionKey = "filter_all")
        BulletinFilter.Documents -> NotesEmptyCopy("no_filtered_documents", actionKey = "filter_all")
        BulletinFilter.Snippets -> NotesEmptyCopy("no_filtered_snippets", actionKey = "filter_all")
        BulletinFilter.Files -> NotesEmptyCopy("no_filtered_files", actionKey = "filter_all")
        BulletinFilter.Pinned -> NotesEmptyCopy("no_filtered_pinned", actionKey = "filter_all")
        BulletinFilter.Todo -> NotesEmptyCopy("no_filtered_todo", actionKey = "filter_all")
        is BulletinFilter.Tag -> NotesEmptyCopy("no_filtered_tag", titleArg = filter.tag, actionKey = "filter_all")
        BulletinFilter.All -> NotesEmptyCopy("no_filtered_notes", actionKey = "filter_all")
    }
    else -> NotesEmptyCopy("no_notes_yet", hintKey = "notes_empty_hint")
}

/** What the Bulletin Board shows when there is nothing to list: no posts yet, or nothing under the chosen filter. */
@Composable
internal fun NotesScope.NotesEmptyState(modifier: Modifier = Modifier) {
    with(frame) { with(live) {
        val isSearchEmpty = searchQuery.isNotBlank() && displayNotes.isNotEmpty()
        val isFilteredEmpty = (activeFilter != BulletinFilter.All || isSearchEmpty) && displayNotes.isNotEmpty()
        val copy = notesEmptyCopy(isSearchEmpty, isFilteredEmpty, activeFilter)
        val accent = if (isCustomGlass) Color(0xFF00E676) else fileApexAccent()
        EmptyState(
            icon = when {
                isSearchEmpty -> Icons.Filled.Search
                isFilteredEmpty -> Icons.Filled.FilterList
                else -> Icons.AutoMirrored.Filled.Note
            },
            title = copy.titleArg?.let { AppI18n.t(copy.titleKey, it) } ?: stringRes(copy.titleKey),
            hint = copy.hintKey?.let { stringRes(it) },
            actionLabel = copy.actionKey?.let { stringRes(it) },
            onAction = copy.actionKey?.let {
                {
                    if (isSearchEmpty) viewModel.setSearchQuery("") else viewModel.setFilter(BulletinFilter.All)
                }
            },
            modifier = modifier,
            colors = EmptyStateColors(icon = accent.copy(alpha = 0.6f), title = textColor, hint = subTextColor, action = accent)
        )
    } }
}
