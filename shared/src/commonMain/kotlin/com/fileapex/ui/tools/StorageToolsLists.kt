package com.fileapex.ui.tools

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.i18n.stringRes
import com.fileapex.platform.CleanupKind
import com.fileapex.platform.DuplicateGroup
import com.fileapex.platform.FolderUsage
import com.fileapex.platform.StorageFileEntry
import com.fileapex.platform.StorageScan
import com.fileapex.platform.childFolders
import com.fileapex.platform.currentTimeMillis
import com.fileapex.platform.formatHostFileSize
import com.fileapex.platform.openLocalFile
import com.fileapex.platform.staleFiles
import com.fileapex.platform.trashLocalEntries
import com.fileapex.ui.DEFAULT_STALE_DAYS
import com.fileapex.ui.ExplorerEntryIcons
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.LocalToolsItemActions
import com.fileapex.ui.RingTrack
import com.fileapex.ui.RingUsed
import com.fileapex.ui.ageChoices
import com.fileapex.ui.explorer.FavoriteMark
import com.fileapex.ui.explorer.ItemContextMenu
import com.fileapex.ui.explorerIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One selectable file line shared by every list page. Long-press opens the same menu as the file manager. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: StorageFileEntry, checked: Boolean, anySelected: Boolean, onToggle: () -> Unit) {
    val actions = LocalToolsItemActions.current
    var menuOpen by remember { mutableStateOf(false) }
    val item = remember(file) {
        RemoteFileItem(
            name = file.name,
            absolutePath = file.path,
            sizeBytes = file.sizeBytes,
            lastModified = file.modifiedMillis,
            isDirectory = false,
            mimeType = file.mimeType
        )
    }
    val open = { openLocalFile(file.path, file.name) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (anySelected) onToggle() else open() },
                    onLongClick = { menuOpen = true }
                )
                .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Box {
                Icon(
                    ExplorerEntryIcons.iconForFile(file.name, file.mimeType),
                    contentDescription = null,
                    tint = RingUsed,
                    modifier = Modifier.size(24.dp)
                )
                FavoriteMark(file.path, Modifier.align(Alignment.TopEnd))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    file.path.substringBeforeLast('/'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(formatHostFileSize(file.sizeBytes), style = MaterialTheme.typography.labelLarge)
        }
        if (actions != null) {
            ItemContextMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                isRemoteTarget = false,
                onCopy = { actions.copy(item) },
                onSendToDevice = { actions.send(item) },
                onDownload = {},
                item = item,
                onOpen = open,
                onSelect = onToggle
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** Bottom bar offering Move to Trash for the current selection; moves go through the system trash. */
@Composable
private fun TrashSelectionBar(
    count: Int,
    bytes: Long,
    working: Boolean,
    onTrash: () -> Unit,
) {
    if (count == 0) return
    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${stringRes("storage_selected_count", count)} · ${formatHostFileSize(bytes)}",
            modifier = Modifier.weight(1f)
        )
        Button(
            enabled = !working,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            onClick = onTrash
        ) {
            Icon(explorerIcon(ExplorerIcon.Delete), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringRes("storage_move_to_trash"))
        }
    }
}

private fun trashPaths(
    scope: CoroutineScope,
    paths: List<String>,
    reload: () -> Unit,
    onError: (String?) -> Unit,
    onDone: () -> Unit,
) {
    scope.launch {
        val outcome = withContext(Dispatchers.IO) { runCatching { trashLocalEntries(paths) } }
        outcome.onSuccess { finished ->
            onError(null)
            if (finished) reload()
        }.onFailure { onError(UserFacingErrors.message(it, "trash_failed")) }
        onDone()
    }
}

@Composable
internal fun FileListPage(
    files: List<StorageFileEntry>,
    scope: CoroutineScope,
    reload: () -> Unit,
    onError: (String?) -> Unit,
    header: @Composable () -> Unit = {},
) {
    var selected by remember(files) { mutableStateOf(emptySet<String>()) }
    var working by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        header()
        if (files.isEmpty()) {
            CenteredNote(stringRes("storage_empty_list"))
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringRes("storage_item_count", files.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                selected = if (selected.size == files.size) emptySet() else files.map { it.path }.toSet()
            }) { Text(stringRes("storage_select_all")) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(files, key = { it.path }) { file ->
                val checked = file.path in selected
                FileRow(file, checked, selected.isNotEmpty()) {
                    selected = if (checked) selected - file.path else selected + file.path
                }
            }
        }
        TrashSelectionBar(
            count = selected.size,
            bytes = files.filter { it.path in selected }.sumOf { it.sizeBytes },
            working = working
        ) {
            working = true
            trashPaths(scope, selected.toList(), reload, onError) {
                selected = emptySet()
                working = false
            }
        }
    }
}

@Composable
internal fun CleanupPage(
    kind: CleanupKind,
    scan: StorageScan,
    scope: CoroutineScope,
    reload: () -> Unit,
    onError: (String?) -> Unit,
) {
    var days by remember { mutableStateOf(DEFAULT_STALE_DAYS) }
    val files = remember(scan, kind, days) { staleFiles(scan.everything, kind, days, currentTimeMillis()) }
    FileListPage(files, scope, reload, onError) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringRes("storage_older_than"), style = MaterialTheme.typography.labelLarge)
            ageChoices.forEach { (value, key) ->
                if (value == days) {
                    Button(onClick = { days = value }) { Text(stringRes(key), maxLines = 1, softWrap = false) }
                } else {
                    OutlinedButton(onClick = { days = value }) { Text(stringRes(key), maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

@Composable
internal fun DuplicatesPage(
    groups: List<DuplicateGroup>?,
    scope: CoroutineScope,
    reload: () -> Unit,
    onError: (String?) -> Unit,
) {
    if (groups == null) {
        CenteredNote(stringRes("storage_duplicates_scan"))
        return
    }
    if (groups.isEmpty()) {
        CenteredNote(stringRes("storage_duplicates_none"))
        return
    }
    var selected by remember(groups) { mutableStateOf(emptySet<String>()) }
    var working by remember { mutableStateOf(false) }
    val all = remember(groups) { groups.flatMap { it.files } }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringRes("storage_reclaimable", formatHostFileSize(groups.sumOf { it.wastedBytes })),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // Files within a group are ordered oldest first, so the first is the one to keep.
            TextButton(onClick = {
                selected = groups.flatMap { it.files.drop(1) }.map { it.path }.toSet()
            }) { Text(stringRes("storage_keep_oldest")) }
        }
        LazyColumn(Modifier.weight(1f)) {
            groups.forEach { group ->
                item(key = "header-${group.files.first().path}") {
                    Text(
                        stringRes("storage_duplicate_group", group.files.size, formatHostFileSize(group.sizeBytes)),
                        style = MaterialTheme.typography.labelLarge,
                        color = RingUsed,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
                    )
                }
                items(group.files, key = { it.path }) { file ->
                    val checked = file.path in selected
                    FileRow(file, checked, selected.isNotEmpty()) {
                        selected = if (checked) selected - file.path else selected + file.path
                    }
                }
            }
        }
        TrashSelectionBar(
            count = selected.size,
            bytes = all.filter { it.path in selected }.sumOf { it.sizeBytes },
            working = working
        ) {
            working = true
            trashPaths(scope, selected.toList(), reload, onError) {
                selected = emptySet()
                working = false
            }
        }
    }
}

@Composable
internal fun FolderSizesPage(
    usages: Map<String, FolderUsage>,
    folderPath: String,
    root: String,
    onDrill: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    val children = remember(usages, folderPath) { childFolders(usages, folderPath) }
    val total = usages[folderPath]?.bytes ?: children.sumOf { it.bytes }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    folderPath.removePrefix(root).ifBlank { "/" },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatHostFileSize(total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onOpenFolder(folderPath) }) { Text(stringRes("storage_open_in_files")) }
        }
        if (children.isEmpty()) {
            CenteredNote(stringRes("storage_empty_list"))
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            items(children, key = { it.path }) { folder ->
                val share = if (total <= 0L) 0f else (folder.bytes.toFloat() / total).coerceIn(0f, 1f)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onDrill(folder.path) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(explorerIcon(ExplorerIcon.Folder), contentDescription = null, tint = RingUsed, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            folder.path.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(formatHostFileSize(folder.bytes), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(RingTrack.copy(alpha = 0.35f))
                    ) {
                        Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(RingUsed))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}
