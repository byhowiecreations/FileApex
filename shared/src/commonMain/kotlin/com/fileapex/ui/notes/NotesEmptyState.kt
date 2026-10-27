package com.fileapex.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BulletinFilter
import com.fileapex.ui.theme.FileApexTeal

/** What the Bulletin Board shows when there is nothing to list: no posts yet, or nothing under the chosen filter. */
@Composable
internal fun NotesScope.NotesEmptyState(modifier: Modifier = Modifier) {
    with(frame) { with(live) {
            val isFilteredEmpty = activeFilter != BulletinFilter.All && displayNotes.isNotEmpty()
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isFilteredEmpty) Icons.Filled.FilterList else Icons.AutoMirrored.Filled.Note,
                        contentDescription = null,
                        tint = if (isCustomGlass) Color(0xFF00E676).copy(alpha = 0.6f) else FileApexTeal.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val emptyMsg = if (isFilteredEmpty) {
                        when (activeFilter) {
                            BulletinFilter.Links -> stringRes("no_filtered_links")
                            BulletinFilter.Images -> stringRes("no_filtered_images")
                            BulletinFilter.Documents -> stringRes("no_filtered_documents")
                            BulletinFilter.Snippets -> stringRes("no_filtered_snippets")
                            BulletinFilter.Files -> stringRes("no_filtered_files")
                            BulletinFilter.Pinned -> stringRes("no_filtered_pinned")
                            BulletinFilter.Todo -> stringRes("no_filtered_todo")
                            is BulletinFilter.Tag -> AppI18n.t("no_filtered_tag", (activeFilter as BulletinFilter.Tag).tag)
                            BulletinFilter.All -> stringRes("no_filtered_notes")
                        }
                    } else {
                        stringRes("no_notes_yet")
                    }
                    Text(
                        text = emptyMsg,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = textColor
                    )
                    if (isFilteredEmpty) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.setFilter(BulletinFilter.All) }) {
                            Text(stringRes("filter_all"), color = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal)
                        }
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringRes("notes_empty_hint"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = subTextColor
                        )
                    }
                }
            }
    } }
}
