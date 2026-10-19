package com.fileapex.data.bulletin

import com.fileapex.util.TimeUtils
import java.util.concurrent.atomic.AtomicLong

/**
 * Source of `updatedAt` stamps. A peer pulls "everything newer than my cursor", so a stamp must never be lower than
 * one this device handed out before, even when the wall clock is set back (a clock that was ahead and got corrected
 * would otherwise hide every later change from peers whose cursor sits in the future).
 */
class BulletinStampClock {
    private val last = AtomicLong(0L)

    fun next(nowMs: Long): Long = last.updateAndGet { previous -> maxOf(nowMs, previous + 1) }

    /** Called with the newest stamp already stored, so a restarted app continues above it. */
    fun observe(storedLatest: Long) {
        last.updateAndGet { previous -> maxOf(previous, storedLatest) }
    }

    companion object {
        val shared = BulletinStampClock()
        fun next(): Long = shared.next(TimeUtils.now())
    }
}
