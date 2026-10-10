package com.fileapex.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.domain.notifications.NotificationKind
import com.fileapex.domain.notifications.NotificationThread
import com.fileapex.i18n.stringRes
import kotlinx.coroutines.launch

/**
 * Lists what the connected phone has shared. Looking at something does not clear it on the phone
 * until the thread is left or the window closes, because a reply needs the notification to still exist.
 * The list is a snapshot from when the window opened.
 */
@Composable
internal fun NotificationListDialog(
    kind: NotificationKind,
    title: String,
    threads: List<NotificationThread>,
    onClear: (List<String>) -> Unit,
    onReply: suspend (key: String, text: String) -> Boolean,
    onDismiss: () -> Unit
) {
    val only = threads.singleOrNull()
    var open by remember { mutableStateOf(only) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val viewed = remember { mutableStateListOf<String>().also { list -> only?.let { list.addAll(it.keys) } } }
    val clear by rememberUpdatedState(onClear)
    val flush = {
        if (viewed.isNotEmpty()) {
            clear(viewed.toList())
            viewed.clear()
        }
    }
    DisposableEffect(Unit) { onDispose { flush() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val thread = open
                if (thread != null && only == null) {
                    IconButton(onClick = { flush(); open = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringRes("back"))
                    }
                }
                Text(open?.title ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            val thread = open
            if (thread != null) {
                NotificationThreadView(thread, canReply = kind == NotificationKind.MESSAGE, onReply = onReply)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                ) {
                    threads.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        if (kind == NotificationKind.OTHER) {
                            val isOpen = item.id in expanded
                            NotificationRow(
                                title = item.title,
                                subtitle = item.lines.lastOrNull().orEmpty(),
                                count = item.keys.size,
                                trailing = if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                onClick = {
                                    expanded = if (isOpen) expanded - item.id else expanded + item.id
                                    if (!isOpen) viewed.addAll(item.keys.filter { it !in viewed })
                                }
                            )
                            if (isOpen) {
                                item.lines.forEach { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 6.dp)
                                    )
                                }
                            }
                        } else {
                            NotificationRow(
                                title = item.title,
                                subtitle = item.lines.lastOrNull().orEmpty(),
                                count = item.keys.size,
                                trailing = null,
                                onClick = {
                                    viewed.addAll(item.keys.filter { it !in viewed })
                                    open = item
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringRes("close")) } }
    )
}

@Composable
private fun NotificationRow(
    title: String,
    subtitle: String,
    count: Int,
    trailing: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (count > 1) {
            Text(
                stringRes("notif_new_count", count.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (trailing != null) {
            Icon(trailing, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun NotificationThreadView(
    thread: NotificationThread,
    canReply: Boolean,
    onReply: suspend (key: String, text: String) -> Boolean
) {
    val scope = rememberCoroutineScope()
    val you = stringRes("notif_you")
    val failedText = stringRes("notif_reply_failed")
    val sent = remember { mutableStateListOf<String>() }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val replyKey = thread.replyKey

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (thread.subText.isNotBlank()) {
                Text(
                    thread.subText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            (thread.lines + sent.map { "$you: $it" }).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (canReply && replyKey != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; failed = false },
                    placeholder = { Text(stringRes("notif_reply_hint")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    enabled = draft.isNotBlank() && !sending,
                    onClick = {
                        val text = draft.trim()
                        sending = true
                        scope.launch {
                            val ok = onReply(replyKey, text)
                            sending = false
                            failed = !ok
                            if (ok) {
                                sent.add(text)
                                draft = ""
                            }
                        }
                    }
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringRes("send")) }
            }
            if (failed) {
                Text(failedText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
