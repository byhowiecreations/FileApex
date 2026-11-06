package com.fileapex.domain.presence

import java.util.concurrent.ConcurrentHashMap

/**
 * A request that reaches us over Iroh names Iroh as the peer's route. A peer only uses Iroh when its LAN route failed, so a
 * LAN label still held for it is checked once (at most once per [minGapMs] per peer) with [lanStillAnswers]: kept when the LAN
 * answers, replaced by Iroh when it does not. Tailscale and Iroh labels are left alone.
 */
internal class InboundIrohRoute(
    private val book: ReachabilityBook,
    private val clock: () -> Long,
    private val minGapMs: Long,
    private val lanStillAnswers: suspend (String) -> Boolean
) {
    private val checkedAt = ConcurrentHashMap<String, Long>()

    suspend fun onInbound(deviceId: String) {
        when (book.via.value[deviceId]) {
            null -> book.noteInboundIroh(deviceId)
            RouteKind.Lan -> {
                val now = clock()
                val last = checkedAt[deviceId]
                if (last != null && now - last < minGapMs) return
                checkedAt[deviceId] = now
                if (!lanStillAnswers(deviceId)) {
                    book.clearRoute(deviceId)
                    book.noteInboundIroh(deviceId)
                }
            }
            else -> Unit
        }
    }
}
