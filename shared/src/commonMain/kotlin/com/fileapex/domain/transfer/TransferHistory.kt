package com.fileapex.domain.transfer

import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TransferHistoryEntry(
    val id: String,
    val fileName: String,
    val deviceName: String,
    val bytes: Long,
    val finishedAtMs: Long
)

/** The last finished sends, kept in memory only: at most [MAX_ENTRIES], each dropped after [MAX_AGE_MS], gone when the app restarts. */
object TransferHistory {
    const val MAX_ENTRIES = 20
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    private val state = MutableStateFlow<List<TransferHistoryEntry>>(emptyList())
    val entries: StateFlow<List<TransferHistoryEntry>> = state.asStateFlow()

    fun record(entry: TransferHistoryEntry, nowMs: Long = TimeUtils.now()) {
        state.value = pruned(listOf(entry) + state.value, nowMs)
    }

    /** The entries still inside the limits at [nowMs], newest first. */
    fun visible(nowMs: Long): List<TransferHistoryEntry> = pruned(state.value, nowMs)

    fun clear() {
        state.value = emptyList()
    }

    private fun pruned(list: List<TransferHistoryEntry>, nowMs: Long): List<TransferHistoryEntry> =
        list.filter { nowMs - it.finishedAtMs <= MAX_AGE_MS }.take(MAX_ENTRIES)
}
