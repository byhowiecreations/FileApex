package com.fileapex.friends

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal const val PAIRED_FIRST_GRACE_MS = 20_000L

/**
 * Paired devices are the trusted ones, so the friends endpoint starts only after the paired roster has published its
 * first online snapshot, or after [graceMs] when no snapshot comes.
 */
internal suspend fun awaitPairedFirst(onlineSnapshotEpochMs: Flow<Long>, graceMs: Long = PAIRED_FIRST_GRACE_MS) {
    withTimeoutOrNull(graceMs) { onlineSnapshotEpochMs.first { it > 0L } }
}

internal suspend fun startAfterPairedFirst(hasFriends: Boolean, onlineSnapshotEpochMs: Flow<Long>, start: suspend () -> Unit) {
    if (!hasFriends) return
    awaitPairedFirst(onlineSnapshotEpochMs)
    start()
}
