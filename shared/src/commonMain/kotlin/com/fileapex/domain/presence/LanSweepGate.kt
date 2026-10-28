package com.fileapex.domain.presence

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Caps how many blocking LAN probes run at once across every subnet sweep. Each probe holds a thread in a blocking
 * connect for up to the probe timeout, so unbounded batches of 32 to 64 per sweep, and several unreachable peers
 * sweeping together, filled the IO pool with dozens of threads that the process then kept.
 */
class LanSweepGate(limit: Int) {
    private val permits = Semaphore(limit)

    suspend fun <T> probe(block: suspend () -> T): T = permits.withPermit { block() }

    companion object {
        const val DEFAULT_LIMIT = 24
        val shared = LanSweepGate(DEFAULT_LIMIT)
    }
}
