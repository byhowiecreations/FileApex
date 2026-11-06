package com.fileapex.domain.presence

/**
 * The short time after a network change in which peers are probed over Iroh whatever their last contact was. Arriving on
 * a LAN never opens it (the peers are reached there anyway). A real move off the LAN is held back only by [leaveGapMs], so
 * a bounce does not probe twice; other events off the LAN, such as a link change on cellular, wait [minGapMs] from the
 * last opening, so a phone that keeps flapping does not probe every peer each time.
 */
class RouteChangeWindow(
    private val clock: () -> Long,
    private val windowMs: Long,
    private val minGapMs: Long,
    private val leaveGapMs: Long = 30_000L
) {
    private var openedAt = Long.MIN_VALUE
    private var wasOnLan: Boolean? = null

    /** Returns true when this change opened the window; [onLan] is null when the caller does not know. */
    @Synchronized
    fun onNetworkChanged(onLan: Boolean? = null): Boolean {
        val leftLan = onLan == false && wasOnLan == true
        if (onLan != null) wasOnLan = onLan
        if (onLan == true) return false
        val now = clock()
        val gap = if (leftLan) leaveGapMs else minGapMs
        if (openedAt != Long.MIN_VALUE && now - openedAt < gap) return false
        openedAt = now
        return true
    }

    @Synchronized
    fun isOpen(): Boolean = openedAt != Long.MIN_VALUE && clock() - openedAt < windowMs
}
