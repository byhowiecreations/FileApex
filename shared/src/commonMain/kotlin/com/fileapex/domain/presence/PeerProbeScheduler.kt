package com.fileapex.domain.presence

import com.fileapex.util.PerKeyRunner
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope

/** How thorough a presence check is: [Full] may search the network for the device, [Light] is a health ping. */
enum class PresenceMode {
    Light,
    Full;

    fun merge(other: PresenceMode): PresenceMode = if (this == Full || other == Full) Full else Light
}

/**
 * Presence is checked per device, never as a pass over all of them. [poke] asks for one device to be checked;
 * that device's own job runs [work] and nothing else waits for it. A poke while the job is running is not lost and
 * not queued behind a lock: the job goes round once more, with the most thorough mode asked for in the meantime.
 */
class PeerProbeScheduler(
    scope: CoroutineScope,
    private val work: suspend (deviceId: String, mode: PresenceMode) -> Unit
) {
    private val runner = PerKeyRunner(scope)
    private val wanted = ConcurrentHashMap<String, PresenceMode>()

    fun poke(deviceId: String, mode: PresenceMode) {
        wanted.merge(deviceId, mode) { old, new -> old.merge(new) }
        runner.launch(deviceId) {
            val mode = wanted.remove(deviceId) ?: return@launch
            work(deviceId, mode)
        }
    }

    fun isRunning(deviceId: String): Boolean = runner.isRunning(deviceId)
}
