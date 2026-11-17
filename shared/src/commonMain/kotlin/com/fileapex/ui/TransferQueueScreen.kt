package com.fileapex.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.domain.transfer.TransferHistory
import com.fileapex.i18n.stringRes
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.presentation.TransferQueueViewModel
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.fileApexTopAppBarColors
import com.fileapex.ui.transfer.DefaultTranslate
import com.fileapex.ui.transfer.TransferRow
import com.fileapex.ui.transfer.TransferRowDensity
import com.fileapex.ui.transfer.TransferRowMappers
import com.fileapex.ui.transfer.TransferStatusFilter
import com.fileapex.ui.transfer.TransferStatusFilters
import com.fileapex.util.TimeUtils

private const val LIVE_ROW_ID = "live"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferQueueScreen(
    onBack: () -> Unit,
    viewModel: TransferQueueViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val liveStats by TransferActivityGuard.statsFlow.collectAsState()
    val history by TransferHistory.entries.collectAsState()
    var filter by remember { mutableStateOf(TransferStatusFilter.All) }

    FileApexBackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringRes("queued_files")) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringRes("back"), color = fileApexChromeContentColor())
                    }
                },
                colors = fileApexTopAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = stringRes("queued_files_intro"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransferStatusFilter.entries.forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(stringRes(option.labelKey)) })
                }
            }

            // Incoming and Drive transfers have no queue row; they still need a Cancel here.
            val liveOnly = liveStats.isActive && liveStats.cancelable && state.items.none { it.isSending }
            val models = buildList {
                if (liveOnly) add(TransferRowMappers.fromLive(liveStats, 0L, stringRes("sending"), DefaultTranslate))
                state.items.forEach { add(TransferRowMappers.fromPending(it, liveStats, DefaultTranslate)) }
                TransferHistory.visible(TimeUtils.now()).forEach { add(TransferRowMappers.fromHistory(it, DefaultTranslate)) }
            }
            val shown = TransferStatusFilters.apply(models, filter)

            if (shown.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.SwapVert,
                    title = stringRes(if (models.isEmpty() || filter == TransferStatusFilter.All) "no_queued_files" else "transfer_history_empty"),
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown, key = { it.id }) { model ->
                        TransferRow(
                            model = model,
                            density = TransferRowDensity.Standard,
                            onCancel = {
                                when {
                                    model.id == LIVE_ROW_ID -> TransferActivityGuard.requestUserCancel()
                                    liveStats.driveRelay -> TransferActivityGuard.requestUserCancel(model.id)
                                    else -> viewModel.cancelSending(model.id)
                                }
                            },
                            onRetry = { viewModel.retryNow(model.id) },
                            onRemove = { viewModel.remove(model.id) }
                        )
                    }
                }
            }
        }
    }
}
