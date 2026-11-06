package com.fileapex.iroh

/**
 * Remembers that the self-hosted relay failed on this device, so the device runs on n0's relays meanwhile and tries
 * the self-hosted one again after a growing wait. The state is local: a failure here may be one network's DNS or
 * firewall, so it never changes what other devices do.
 */
class IrohRelayFallback(private val clock: () -> Long) {
    private var failures = 0
    private var retryAt = 0L
    private var followed: IrohRelayConfig? = null

    val isFallback: Boolean get() = failures > 0

    fun retryDue(): Boolean = isFallback && clock() >= retryAt

    fun shouldUseCustom(): Boolean = !isFallback || retryDue()

    fun customFailed() {
        failures++
        retryAt = clock() + waitMs(failures)
    }

    fun customWorked() = reset()

    /** A different relay (or none) than before starts over; the same one keeps its fallback state. */
    fun follow(wanted: IrohRelayConfig?) {
        if (wanted == followed) return
        followed = wanted
        reset()
    }

    fun reset() {
        failures = 0
        retryAt = 0L
    }

    private fun waitMs(failureCount: Int): Long =
        (FIRST_WAIT_MS shl (failureCount - 1).coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(MAX_WAIT_MS)

    private companion object {
        const val FIRST_WAIT_MS = 5 * 60_000L
        const val MAX_WAIT_MS = 30 * 60_000L
        const val MAX_DOUBLINGS = 3
    }
}

/** What [IrohRelayStarter] started: the endpoint and whether it runs on the self-hosted relay. */
class IrohRelayStart(val link: IrohLinkHandle, val usedCustom: Boolean)

/**
 * Starts the endpoint on the self-hosted relay when one is wanted and the fallback allows it. An endpoint that does
 * not come online on it is shut down and replaced by one on n0's relays.
 */
class IrohRelayStarter(val fallback: IrohRelayFallback) {
    suspend fun start(engine: IrohEngine, secretKey: ByteArray, wanted: IrohRelayConfig?): IrohRelayStart {
        if (wanted == null || !fallback.shouldUseCustom()) return IrohRelayStart(engine.start(secretKey, null), false)
        val onCustom = engine.start(secretKey, wanted)
        if (onCustom.online) {
            fallback.customWorked()
            return IrohRelayStart(onCustom, true)
        }
        onCustom.close()
        fallback.customFailed()
        return IrohRelayStart(engine.start(secretKey, null), false)
    }
}
