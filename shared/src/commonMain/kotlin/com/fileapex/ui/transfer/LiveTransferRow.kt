package com.fileapex.ui.transfer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.i18n.stringRes
import kotlinx.coroutines.delay

/** The running batch as one row, with the quiet-stream warning and Cancel. */
@Composable
fun LiveTransferRow(
    density: TransferRowDensity,
    modifier: Modifier = Modifier,
    accent: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    textColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
    mutedColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
) {
    val stats by TransferActivityGuard.statsFlow.collectAsState()
    var silentSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(stats.isActive) {
        while (stats.isActive) {
            silentSeconds = TransferActivityGuard.millisSinceProgress() / 1000L
            delay(1_000L)
        }
        silentSeconds = 0L
    }
    val fallback = stringRes("sending")
    val model = TransferRowMappers.fromLive(stats, silentSeconds, fallback, DefaultTranslate)
    TransferRow(
        model = model,
        density = density,
        onCancel = { TransferActivityGuard.requestUserCancel() },
        onRetry = {},
        onRemove = {},
        modifier = modifier,
        accent = accent,
        textColor = textColor,
        mutedColor = mutedColor
    )
}
