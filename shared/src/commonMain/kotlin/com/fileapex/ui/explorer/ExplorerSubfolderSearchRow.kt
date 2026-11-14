package com.fileapex.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.ExplorerSortMode

@Composable
internal fun ExplorerFilterBarWithSubfolders(
    frame: ExplorerFrame,
    sortMode: ExplorerSortMode,
    onSortModeChange: (ExplorerSortMode) -> Unit,
    leading: (@Composable (modifier: Modifier, compact: Boolean) -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ExplorerFilterBar(
            query = frame.filterQuery,
            onQueryChange = { frame.filterQuery = it },
            sortMode = sortMode,
            onSortModeChange = onSortModeChange,
            leading = leading
        )
        if (frame.canSearchSubfolders && frame.filterQuery.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = frame.searchSubfolders,
                    onClick = { frame.searchSubfolders = !frame.searchSubfolders },
                    label = { Text(stringRes("search_subfolders")) }
                )
                if (frame.searchSubfolders) {
                    val count = frame.visibleDirectories.size + frame.visibleFiles.size
                    Text(
                        text = stringRes("search_subfolders_count", count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
