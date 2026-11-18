package com.fileapex.ui.transfer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.ui.FileApexIcons

/**
 * The one row for a file that is moving or waiting. [TransferRowDensity.Compact] is the floating banner,
 * [TransferRowDensity.Standard] the queue and friends lists, [TransferRowDensity.Inline] dialogs and the share screen.
 */
@Composable
fun TransferRow(
    model: TransferRowModel,
    density: TransferRowDensity,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    mutedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    when (density) {
        TransferRowDensity.Compact -> CompactRow(model, onCancel, modifier, accent, textColor, mutedColor)
        TransferRowDensity.Standard -> StandardRow(model, onCancel, onRetry, onRemove, modifier, accent, textColor, mutedColor)
        TransferRowDensity.Inline -> InlineRow(model, onCancel, modifier, accent, textColor)
    }
}

private fun cancelIconFor(model: TransferRowModel): ImageVector = if (model.cancelPauses) FileApexIcons.Pause else Icons.Filled.Close

private fun cancelLabelKeyFor(model: TransferRowModel): String = if (model.cancelPauses) "transfer_pause" else "cancel"

private fun iconFor(model: TransferRowModel): ImageVector = when (model.state) {
    TransferRowState.Done -> Icons.Filled.Check
    TransferRowState.Failed, TransferRowState.Refused -> Icons.Filled.Warning
    TransferRowState.Canceled -> Icons.Filled.Close
    else -> if (model.isSend) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward
}

@Composable
private fun Bar(model: TransferRowModel, accent: Color, modifier: Modifier = Modifier) {
    val progress = model.progress
    when {
        progress != null && model.working -> LinearProgressIndicator(
            progress = { progress }, modifier = modifier, color = accent, trackColor = accent.copy(alpha = 0.2f)
        )
        model.working -> LinearProgressIndicator(modifier = modifier, color = accent, trackColor = accent.copy(alpha = 0.2f))
    }
}

@Composable
private fun CompactRow(model: TransferRowModel, onCancel: () -> Unit, modifier: Modifier, accent: Color, textColor: Color, mutedColor: Color) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(iconFor(model), contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val label = if (model.peer.isNotBlank()) "${model.title} → ${model.peer}" else model.title
                Text(
                    label, Modifier.weight(1f), color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                if (model.statusText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(model.statusText, color = accent, maxLines = 1, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                }
            }
            Spacer(Modifier.height(6.dp))
            Bar(model, accent, Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
        }
        if (model.canCancel) {
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(cancelIconFor(model), contentDescription = stringRes(cancelLabelKeyFor(model)), tint = mutedColor, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StandardRow(
    model: TransferRowModel, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit,
    modifier: Modifier, accent: Color, textColor: Color, mutedColor: Color
) {
    val errorColor = MaterialTheme.colorScheme.error
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(iconFor(model), contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.title, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
            val line = listOf(model.peer, model.statusText).filter { it.isNotBlank() }.joinToString(" • ")
            if (line.isNotBlank()) Text(line, color = mutedColor, style = MaterialTheme.typography.bodySmall)
            model.error?.let { Text(it, color = errorColor, style = MaterialTheme.typography.bodySmall) }
            model.warning?.let { Text(it, color = errorColor, style = MaterialTheme.typography.bodySmall) }
            Bar(model, accent, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (model.canCancel) IconButton(onClick = onCancel) { Icon(cancelIconFor(model), contentDescription = stringRes(cancelLabelKeyFor(model))) }
        if (model.canRetry) IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = stringRes("queue_retry_now")) }
        if (model.canRemove) IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = stringRes("remove_from_queue")) }
    }
}

@Composable
private fun InlineRow(model: TransferRowModel, onCancel: () -> Unit, modifier: Modifier, accent: Color, textColor: Color) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(model.title, Modifier.weight(1f), color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (model.statusText.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(model.statusText, color = accent, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        Bar(model, accent, Modifier.fillMaxWidth())
        model.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp)) }
        if (model.canCancel) {
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(stringRes(cancelLabelKeyFor(model))) }
        }
    }
}
