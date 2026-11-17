package com.fileapex.ui.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.i18n.stringRes
import com.fileapex.util.sha256HexFileStreaming
import java.io.IOException
import kotlinx.coroutines.CancellationException

@Composable
internal fun ItemChecksumDialog(item: RemoteFileItem, onClose: () -> Unit) {
    var bytesRead by remember(item.id) { mutableLongStateOf(0L) }
    var hash by remember(item.id) { mutableStateOf<String?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        try {
            hash = sha256HexFileStreaming(item.absolutePath) { bytesRead = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(item.name, maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(stringRes("checksum_sha256"), style = MaterialTheme.typography.labelMedium)
                val result = hash
                when {
                    failed -> Text(stringRes("checksum_failed"), color = MaterialTheme.colorScheme.error)
                    result != null -> SelectionContainer { Text(result, style = MaterialTheme.typography.bodyMedium) }
                    else -> {
                        Text(stringRes("checksum_calculating"), style = MaterialTheme.typography.bodyMedium)
                        val fraction = if (item.sizeBytes > 0L) (bytesRead.toFloat() / item.sizeBytes).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringRes(if (hash != null || failed) "close" else "cancel")) }
        }
    )
}

@Composable
internal fun ItemRenameDialog(
    text: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("rename")) },
        text = {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            // Wraps over several lines so a long name is fully visible and every part can be tapped.
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = false,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringRes("rename")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringRes("cancel")) } }
    )
}
