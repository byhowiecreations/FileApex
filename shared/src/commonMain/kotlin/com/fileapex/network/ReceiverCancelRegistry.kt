package com.fileapex.network

import com.fileapex.util.TimeUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * Transfers the receiving user cancelled. The sender learns of it from a 410 on its next request
 * for the same transaction and stops instead of retrying. Entries expire so a later send of the
 * same file (new transaction id) is never affected.
 */
object ReceiverCancelRegistry {
    const val CANCELLED_BODY = "cancelled_by_receiver"
    private const val TTL_MS = 10 * 60 * 1000L
    private val cancelledAtMs = ConcurrentHashMap<String, Long>()

    fun mark(key: String) {
        if (key.isBlank()) return
        val now = TimeUtils.now()
        cancelledAtMs.entries.removeIf { now - it.value > TTL_MS }
        cancelledAtMs[key] = now
    }

    fun isCancelled(key: String): Boolean {
        if (key.isBlank()) return false
        val at = cancelledAtMs[key] ?: return false
        if (TimeUtils.now() - at > TTL_MS) {
            cancelledAtMs.remove(key)
            return false
        }
        return true
    }
}
