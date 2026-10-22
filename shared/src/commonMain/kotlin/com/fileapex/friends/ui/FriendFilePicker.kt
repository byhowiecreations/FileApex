package com.fileapex.friends.ui

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
import androidx.compose.material3.Checkbox
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
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.explorerIcon
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a friend send picks its files: the app's own browser on every platform, like the rest of the app. Several files can be ticked. */
@Composable
fun rememberFriendFilePicker(onPicked: (List<File>) -> Unit): () -> Unit {
    var open by remember { mutableStateOf(false) }
    if (open) FriendFilePickerDialog(onPick = { open = false; onPicked(it) }, onDismiss = { open = false })
    return { open = true }
}

@Composable
private fun FriendFilePickerDialog(onPick: (List<File>) -> Unit, onDismiss: () -> Unit) {
    val root = remember { defaultStorageRoot().trimEnd('/') }
    var path by remember { mutableStateOf(File(root, "Downloads").takeIf { it.isDirectory }?.absolutePath ?: root) }
    var entries by remember { mutableStateOf<List<RemoteFileItem>>(emptyList()) }
    var ticked by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(path) {
        entries = withContext(Dispatchers.IO) {
            runCatching { fastScanDirectory(path).let { it.first + it.second } }.getOrDefault(emptyList())
                .filter { !it.name.startsWith(".") }
                .sortedWith(compareByDescending<RemoteFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("friends_send_file")) },
        text = {
            Column {
                Text(path, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    if (path.trimEnd('/') != root) {
                        item { PickerRow(stringRes("up"), folder = true) { path = path.trimEnd('/').substringBeforeLast('/').ifBlank { root } } }
                    }
                    items(entries, key = { it.absolutePath }) { entry ->
                        if (entry.isDirectory) PickerRow(entry.name, folder = true) { path = entry.absolutePath }
                        else PickerRow(entry.name, folder = false, checked = entry.absolutePath in ticked) {
                            ticked = if (entry.absolutePath in ticked) ticked - entry.absolutePath else ticked + entry.absolutePath
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = ticked.isNotEmpty(), onClick = { onPick(ticked.map { File(it) }.filter { it.isFile }) }) { Text(stringRes("send")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringRes("cancel")) } }
    )
}

@Composable
private fun PickerRow(label: String, folder: Boolean, checked: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (folder) Icon(explorerIcon(ExplorerIcon.Folder), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        else Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
