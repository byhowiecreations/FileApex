package com.fileapex.ui.carousel

import kotlin.math.exp

object CarouselMath {
    /** [index] folded into 0 until [count], so the row has no ends. */
    fun wrap(index: Int, count: Int): Int {
        require(count > 0)
        return ((index % count) + count) % count
    }

    /** Signed steps from [center] to [index] along the shorter way round, in -count/2..count/2. */
    fun shortestOffset(index: Int, center: Int, count: Int): Int {
        require(count > 0)
        var d = wrap(index - center, count)
        if (d > count / 2) d -= count
        return d
    }

    /** Visual scale of a card [offset] places from the centre: 1 in the middle, smaller further out. */
    fun cardScale(offset: Float): Float {
        val d = kotlin.math.abs(offset)
        // The focused card is 10% larger than a plain unit card; from one place out the size falls 12% per place.
        return if (d < 1f) FOCUSED_SCALE - (FOCUSED_SCALE - 0.88f) * d else (1f - 0.12f * d).coerceAtLeast(0.7f)
    }

    private const val FOCUSED_SCALE = 1.1f

    fun cardAlpha(offset: Float): Float = (1f - 0.55f * kotlin.math.abs(offset)).coerceIn(0f, 1f)

    /** Dock icon scale for a pointer [distancePx] from the icon centre; 1 when the pointer is far away. */
    /** The icon under the pointer is 1.925 times its size, 10% more than the earlier 1.75. */
    const val DOCK_PEAK_EXTRA = 0.925f

    fun dockScale(distancePx: Float, sigmaPx: Float = 55f, extra: Float = DOCK_PEAK_EXTRA): Float {
        val r = distancePx / sigmaPx
        return 1f + extra * exp(-(r * r))
    }

    /** How much wider the dock bar gets: the extra width of every magnified icon, so the icons never leave it. */
    fun dockPillGrowth(extraPx: List<Float>): Float = extraPx.sum()

    /**
     * Sideways shift of each icon from its resting place. The row is laid out with half the growth added to its left
     * padding and the bar is centred, so the bar grows evenly and each icon lands exactly where the icons before it have
     * pushed it, with the original gap kept.
     */
    fun dockShifts(extraPx: List<Float>): List<Float> {
        val half = dockPillGrowth(extraPx) / 2f
        var before = 0f
        return extraPx.map { extra -> (-half + before + extra / 2f).also { before += extra } }
    }
}

/**
 * Turns scroll events into carousel steps: one card, two for a strong swipe, never more in one swipe. A swipe ends
 * when events pause, when the direction flips, or when the scroll speeds up again after trailing off, so a new
 * swipe works right after a free-spinning wheel or trackpad momentum without stopping the pointer first.
 */
class CarouselScrollGate(private val step: Float = 1f, private val quietMs: Long = 150L) {
    private var acc = 0f
    private var steps = 0
    private var lastEvent = Long.MIN_VALUE / 2
    private var lastStep = Long.MIN_VALUE / 2
    private var lastSize = 0f
    private var lastSign = 0

    /** -1, 0 or 1: how many cards to move for this event. */
    fun onScroll(amount: Float, nowMs: Long): Int {
        if (amount == 0f) return 0
        val sign = if (amount > 0) 1 else -1
        val size = kotlin.math.abs(amount)
        val settled = nowMs - lastStep >= RESTART_AFTER_STEP_MS
        val reversed = lastSign != 0 && sign != lastSign
        val speedingUp = steps > 0 && size > lastSize * 2f + 0.3f
        if (nowMs - lastEvent > quietMs || (settled && (reversed || speedingUp))) {
            acc = 0f
            steps = 0
        }
        lastEvent = nowMs
        lastSize = size
        lastSign = sign
        acc += amount
        val dir = if (acc > 0) 1 else -1
        if (steps == 0 && kotlin.math.abs(acc) >= step) {
            steps = 1; lastStep = nowMs
            return dir
        }
        if (steps == 1 && kotlin.math.abs(acc) >= step * 5 && nowMs - lastStep >= SECOND_STEP_AFTER_MS) {
            steps = 2; lastStep = nowMs
            return dir
        }
        return 0
    }

    private companion object {
        const val RESTART_AFTER_STEP_MS = 220L
        const val SECOND_STEP_AFTER_MS = 160L
    }
}
