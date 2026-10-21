package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

private const val SNAPSHOT_MIN_GAP_MS = 5_000L

/** Refreshes when [changes] emits and when [nextExpiryWaitMs] says an online device is about to expire; never more often than every 5 s. */
internal suspend fun watchOnlineSnapshot(
    changes: Flow<*>,
    refresh: suspend () -> Unit,
    nextExpiryWaitMs: suspend () -> Long?
) = coroutineScope {
    val changed = Channel<Unit>(Channel.CONFLATED)
    launch { changes.drop(1).collect { changed.trySend(Unit) } }
    while (isActive) {
        refresh()
        val wait = nextExpiryWaitMs()
        if (wait == null) changed.receive() else withTimeoutOrNull(wait) { changed.receive() }
        delay(SNAPSHOT_MIN_GAP_MS)
    }
}

/** How long until the first online device passes the presence threshold, or null when none is online. */
internal fun onlineExpiryWaitMs(
    devices: List<PairedDeviceEntity>,
    online: Set<String>,
    reachabilityEpochMs: Map<String, Long>,
    now: Long
): Long? {
    val soonest = devices.filter { it.deviceId in online }.minOfOrNull {
        maxOf(reachabilityEpochMs[it.deviceId] ?: 0L, it.lastSeenEpochMs) + LanPresenceTiming.PRESENCE_READY_THRESHOLD_MS
    } ?: return null
    return (soonest - now + 1_000L).coerceIn(1_000L, LanPresenceTiming.PRESENCE_READY_THRESHOLD_MS)
}
