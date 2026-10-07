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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.i18n.stringRes

/** Seconds without a single byte before the block says so. */
private const val STALL_NOTICE_SECONDS = 15L

/** Live batch progress with file name, percent, speed and a cancel action for outbound sends. */
@Composable
fun TransferProgressBlock(modifier: Modifier = Modifier) {
    val stats by TransferActivityGuard.statsFlow.collectAsState()
    var silentSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(stats.isActive) {
        while (stats.isActive) {
            silentSeconds = TransferActivityGuard.millisSinceProgress() / 1000L
            delay(1_000L)
        }
        silentSeconds = 0L
    }
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
        if (silentSeconds >= STALL_NOTICE_SECONDS) {
            Text(
                text = stringRes("transfer_no_data", silentSeconds.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (stats.cancelable) {
            TextButton(
                onClick = { TransferActivityGuard.requestUserCancel() },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringRes("cancel"))
            }
        }
    }
}
