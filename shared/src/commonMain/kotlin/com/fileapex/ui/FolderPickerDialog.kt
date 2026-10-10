package com.fileapex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.i18n.stringRes
import com.fileapex.platform.defaultStorageRoot
import com.fileapex.platform.fastScanDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Browse folders on this device and pick one. Never goes above the storage root. */
@Composable
internal fun FolderPickerDialog(
    startPath: String,
    title: String,
    confirmLabel: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val root = remember { defaultStorageRoot().trimEnd('/') }
    var path by remember { mutableStateOf(startPath.ifBlank { root }) }
    var folders by remember { mutableStateOf<List<RemoteFileItem>>(emptyList()) }
    LaunchedEffect(path) {
        folders = withContext(Dispatchers.IO) {
            runCatching { fastScanDirectory(path).first.sortedBy { it.name.lowercase() } }.getOrDefault(emptyList())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(path, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (path.trimEnd('/') != root) {
                        item {
                            PickerRow(stringRes("up")) { path = path.trimEnd('/').substringBeforeLast('/').ifBlank { root } }
                        }
                    }
                    items(folders, key = { it.absolutePath }) { folder ->
                        PickerRow(folder.name) { path = folder.absolutePath }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringRes("cancel")) } }
    )
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(explorerIcon(ExplorerIcon.Folder), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
