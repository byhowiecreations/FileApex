package com.fileapex.domain.peer

import com.fileapex.util.TimeUtils
import java.util.concurrent.atomic.AtomicLong

/**
 * Hybrid logical clock for cluster membership versions, in UTC epoch ms.
 *
 * A stamp from [next] is greater than every stamp this node has issued or accepted, so a
 * re-pair made after seeing a removal always outranks it even when device wall clocks disagree.
 * Receivers never mint versions for incoming events; they only compare and [observe].
 */
object ClusterClock {
    /** Membership stamps from builds before 0.14.3a are wall-clock re-stamps and cannot order events. */
    const val MEMBERSHIP_PROTOCOL = 2

    /** A peer clock this far ahead would freeze everyone else's events behind it. */
    const val MAX_FORWARD_SKEW_MS = 10L * 60L * 1000L

    private val last = AtomicLong(0L)

    fun next(): Long = last.updateAndGet { previous -> maxOf(TimeUtils.now(), previous + 1L) }

    fun isAcceptable(version: Long): Boolean =
        version > 0L && version <= TimeUtils.now() + MAX_FORWARD_SKEW_MS

    fun observe(version: Long) {
        if (isAcceptable(version)) {
            last.accumulateAndGet(version, ::maxOf)
        }
    }
}
