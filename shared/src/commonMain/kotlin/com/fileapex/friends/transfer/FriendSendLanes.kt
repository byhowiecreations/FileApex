package com.fileapex.friends.transfer

import com.fileapex.network.TransferRuntime

/**
 * How many files go to one friend at the same time: up to [maxLanes], but only one file of [largeBytes] or more at once,
 * so a big file keeps the link while small ones go alongside it (the same rule as the paired-device scheduler).
 */
class FriendSendLanes(
    private val queue: FriendsSendQueue,
    private val maxLanes: Int = MAX_LANES,
    private val largeBytes: Long = TransferRuntime.LARGE_FILE_THRESHOLD_BYTES
) {
    private val lanes = HashMap<String, Int>()
    private val largeInFlight = HashSet<String>()

    /** After the queue grew: how many new lanes to start for [friendKey]. */
    @Synchronized fun lanesToStart(friendKey: String): Int {
        val active = lanes[friendKey] ?: 0
        val n = minOf(maxLanes - active, queue.idsFor(friendKey).size).coerceAtLeast(0)
        if (n > 0) lanes[friendKey] = active + n
        return n
    }

    /** The next file for a lane, or null when the lane should end. */
    @Synchronized fun next(friendKey: String): FriendsSendQueue.Item? {
        val item = queue.take(friendKey, skipLarge = friendKey in largeInFlight, largeBytes = largeBytes)
        if (item == null) {
            val left = (lanes[friendKey] ?: 1) - 1
            if (left <= 0) lanes.remove(friendKey) else lanes[friendKey] = left
            return null
        }
        if (item.file.length() >= largeBytes) largeInFlight += friendKey
        return item
    }

    @Synchronized fun finished(friendKey: String, item: FriendsSendQueue.Item) {
        if (item.file.length() >= largeBytes) largeInFlight -= friendKey
    }

    companion object { const val MAX_LANES = 4 }
}
