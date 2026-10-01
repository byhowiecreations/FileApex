package com.fileapex.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.i18n.stringRes

/** Live batch progress with file name, percent, speed and a cancel action for outbound sends. */
@Composable
fun TransferProgressBlock(modifier: Modifier = Modifier) {
    val stats by TransferActivityGuard.statsFlow.collectAsState()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stats.currentFileName.ifBlank { stringRes("sending") },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = buildList {
                    if (stats.speedFormatted.isNotBlank()) add(stats.speedFormatted)
                    add("${(stats.progress * 100).toInt().coerceIn(0, 100)}%")
                }.joinToString(" • "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (stats.totalBytes > 0L) {
            LinearProgressIndicator(progress = { stats.progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (stats.cancelable) {
            TextButton(
                onClick = { TransferActivityGuard.cancelActiveTransfers() },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringRes("cancel"))
            }
        }
    }
}
