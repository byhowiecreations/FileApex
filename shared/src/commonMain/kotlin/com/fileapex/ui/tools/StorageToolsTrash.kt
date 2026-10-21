package com.fileapex.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.i18n.stringRes
import com.fileapex.platform.TrashedEntry
import com.fileapex.platform.formatHostFileSize
import com.fileapex.platform.purgeTrashed
import com.fileapex.platform.restoreTrashed
import com.fileapex.ui.ExplorerEntryIcons
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.ExplorerIconFamily
import com.fileapex.ui.RingUsed
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.isFileApexTiledChrome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TrashPage(
    trash: List<TrashedEntry>,
    scope: CoroutineScope,
    reload: () -> Unit,
    onError: (String?) -> Unit,
) {
    var selected by remember(trash) { mutableStateOf(emptySet<String>()) }
    var working by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<List<TrashedEntry>?>(null) }
    if (trash.isEmpty()) {
        CenteredNote(stringRes("trash_empty_state"))
        return
    }
    fun run(ids: List<String>, action: (List<String>) -> Boolean) {
        scope.launch {
            working = true
            val outcome = withContext(Dispatchers.IO) { runCatching { action(ids) } }
            outcome.onSuccess { finished ->
                onError(null)
                if (finished) reload()
            }.onFailure { onError(UserFacingErrors.message(it, "trash_failed")) }
            selected = emptySet()
            working = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringRes("storage_item_count", trash.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                selected = if (selected.size == trash.size) emptySet() else trash.map { it.id }.toSet()
            }) { Text(stringRes("storage_select_all")) }
            TextButton(enabled = !working, onClick = { confirmPurge = trash }) {
                Text(stringRes("trash_empty"), color = MaterialTheme.colorScheme.error)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(trash, key = { it.id }) { entry ->
                val checked = entry.id in selected
                val toggle = { selected = if (checked) selected - entry.id else selected + entry.id }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = toggle)
                        .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                    Icon(
                        if (entry.isDirectory) explorerIcon(ExplorerIcon.Folder)
                        else ExplorerEntryIcons.iconForFile(entry.name, ""),
                        contentDescription = null,
                        tint = RingUsed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        entry.originalPath?.let {
                            Text(
                                stringRes("trash_from_location", it.substringBeforeLast('/')),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(formatHostFileSize(entry.sizeBytes), style = MaterialTheme.typography.labelLarge)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        if (selected.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringRes("storage_selected_count", selected.size), modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !working, onClick = { run(selected.toList(), ::restoreTrashed) }) {
                    Icon(explorerIcon(ExplorerIcon.Restore), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringRes("trash_restore"))
                }
                Button(
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { confirmPurge = trash.filter { it.id in selected } }
                ) { Text(stringRes("trash_delete_forever")) }
            }
        }
    }
    confirmPurge?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmPurge = null },
            title = { Text(stringRes("trash_purge_title")) },
            text = {
                Text(stringRes("trash_purge_body", "${doomed.size} · ${formatHostFileSize(doomed.sumOf { it.sizeBytes })}"))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurge = null
                    run(doomed.map { it.id }, ::purgeTrashed)
                }) { Text(stringRes("trash_delete_forever"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = null }) { Text(stringRes("cancel")) } }
        )
    }
}

/**
 * Folder with a gear tucked in the corner. The gear sits in a cut-out so both read clearly on any background.
 */
@Composable
internal fun ToolsGlyph(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val gear = size * 0.52f
    Box(modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        Icon(
            explorerIcon(ExplorerIcon.Folder, ExplorerIconFamily.Outlined),
            contentDescription = stringRes("tools"),
            tint = tint,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(gear + 4.dp)
                .drawBehind { drawCircle(Color.Black, radius = this.size.minDimension / 2f, blendMode = BlendMode.Clear) }
        )
        Icon(
            Icons.Filled.Settings,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(gear)
        )
    }
}

/** Header button that opens Tools; matches the raised tiles next to it on Jaded Steel. */
@Composable
internal fun ToolsHeaderButton(onClick: () -> Unit, tint: Color, modifier: Modifier = Modifier) {
    if (isFileApexTiledChrome()) {
        Box(modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            JadedRaisedTile(tileSize = 28.dp) { ToolsGlyph(tint = fileApexTileTint(), size = 18.dp) }
        }
    } else {
        IconButton(onClick = onClick, modifier = modifier) { ToolsGlyph(tint = tint) }
    }
}
