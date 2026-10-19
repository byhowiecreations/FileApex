package com.fileapex.network

import java.util.concurrent.ConcurrentHashMap

/**
 * Counts rejected or anonymous requests per source address in a sliding window so a scanner is slowed down
 * instead of answered at full speed. Holds only a short list of timestamps per address, in memory.
 */
class AuthFailureThrottle(private val limit: Int = 30, private val windowMs: Long = 60_000L) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Records one request from [source] and returns true when it is over the limit for the window. */
    fun hitAndCheck(source: String, nowMs: Long): Boolean {
        val queue = hits.computeIfAbsent(source) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && nowMs - queue.first() > windowMs) queue.removeFirst()
            queue.addLast(nowMs)
            return queue.size > limit
        }
    }

    fun clearIdle(nowMs: Long) {
        hits.entries.removeIf { (_, queue) -> synchronized(queue) { queue.isEmpty() || nowMs - queue.last() > windowMs } }
    }
}
