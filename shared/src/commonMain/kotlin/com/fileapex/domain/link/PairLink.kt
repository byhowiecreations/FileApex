package com.fileapex.domain.link

import kotlinx.serialization.Serializable

/**
 * What a device remembers about its partner on Simple Home: the peer that last announced it is showing this device.
 * [chosenAtEpochMs] is when that peer's user chose it with Switch device, or 0 when it was only the peer's guess;
 * for a release it is the time the peer's user switched to another device.
 */
@Serializable
data class LinkRecord(
    val peerId: String,
    val active: Boolean,
    val chosenAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

/** The rules that keep two devices on Simple Home showing each other. */
object PairLink {
    /** A released record, and the notification grant, cover this long after the last announcement that carried them. An active link has no lapse. */
    const val LEASE_MS = 10 * 60 * 1000L

    /**
     * One announcement: [active] says the sender shows the receiver. [chosenAtEpochMs] is the time the sender's user
     * chose it, 0 for a guess. An inactive signal with a time is a release (the user switched to another device); one
     * with no time is only a pause, such as leaving the screen, and changes nothing.
     */
    data class Signal(val active: Boolean, val chosenAtEpochMs: Long)

    /** The device to show: [choiceId] is the user's own choice to keep, [followId] a partner to follow, [excludedIds] are peers a guess must skip. */
    data class Decision(val choiceId: String, val followId: String, val excludedIds: Set<String>)

    fun activeValue(signal: Signal): String = if (signal.active) "1" else "0"

    fun chosenAtValue(signal: Signal): Long = signal.chosenAtEpochMs

    /** An older build sends only the flag, which is then a guess or a pause. */
    fun parse(active: String?, chosenAt: String?): Signal? {
        val isActive = when (active?.trim()) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        return Signal(isActive, chosenAt?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: 0L)
    }

    /**
     * What to keep after [peerId] announced [signal]. A link only ends or moves when a user changes it: a pause changes
     * nothing, a release from the partner ends it, and only a newer choice replaces a standing link; a guess never does.
     * An active record has no lapse: [LinkRecord.expiresAtEpochMs] only ends a released one.
     */
    fun merge(existing: LinkRecord?, peerId: String, signal: Signal, now: Long): LinkRecord? {
        val live = existing != null && (existing.active || now < existing.expiresAtEpochMs)
        if (!signal.active) {
            if (signal.chosenAtEpochMs == 0L) return existing
            if (live && existing!!.active) {
                if (existing.peerId != peerId || signal.chosenAtEpochMs < existing.chosenAtEpochMs) return existing
            }
            return LinkRecord(peerId, false, signal.chosenAtEpochMs, now + LEASE_MS)
        }
        val incoming = LinkRecord(peerId, true, signal.chosenAtEpochMs, now + LEASE_MS)
        if (live && existing!!.peerId == peerId && existing.active && signal.chosenAtEpochMs == 0L) {
            // A plain renewal (older builds send nothing else) does not forget when the choice was made.
            return incoming.copy(chosenAtEpochMs = existing.chosenAtEpochMs)
        }
        if (!live || existing!!.peerId == peerId || !existing.active) return incoming
        return if (signal.chosenAtEpochMs > existing.chosenAtEpochMs) incoming else existing
    }

    fun decide(chosenId: String, chosenAtEpochMs: Long, claim: LinkRecord?, now: Long): Decision {
        val own = Decision(chosenId, "", emptySet())
        if (claim == null) return own
        if (claim.active) {
            return when {
                chosenId.isEmpty() -> Decision("", claim.peerId, emptySet())
                claim.peerId == chosenId -> own
                claim.chosenAtEpochMs == 0L || chosenAtEpochMs >= claim.chosenAtEpochMs -> own
                else -> Decision("", claim.peerId, emptySet())
            }
        }
        if (now >= claim.expiresAtEpochMs) return own
        val partnerLeftAfterMyChoice = chosenId == claim.peerId && claim.chosenAtEpochMs > chosenAtEpochMs
        return when {
            chosenId.isEmpty() || partnerLeftAfterMyChoice -> Decision("", "", setOf(claim.peerId))
            else -> own
        }
    }
}
