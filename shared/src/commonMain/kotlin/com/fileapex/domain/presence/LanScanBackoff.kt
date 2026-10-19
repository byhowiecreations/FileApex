package com.fileapex.domain.presence

import com.fileapex.util.NetworkUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers that a subnet scan for a peer found nothing, so the next sweeps do not scan again at once.
 * The wait doubles per failed scan and is capped; a successful reach forgets it. A failure only counts on
 * the network it was recorded on ([networkKey], the local /24s), so moving to another LAN scans at once.
 * Explicit primes, mDNS finds and a wake from a known peer bypass this for that one peer. Peers and
 * pairing candidates share one instance.
 */
class LanScanBackoff(
    private val baseMs: Long,
    private val maxMs: Long,
    private val now: () -> Long,
    saved: String = "",
    private val persist: (String) -> Unit = {}
) {
    private class Entry(val networkKey: String, val failures: Int, val nextScanAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        for (line in saved.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size != 4) continue
            val failures = parts[2].toIntOrNull() ?: continue
            val nextScanAtMs = parts[3].toLongOrNull() ?: continue
            if (parts[0].isEmpty() || failures < 1 || nextScanAtMs > now() + maxMs) continue
            entries[parts[0]] = Entry(parts[1], failures, nextScanAtMs)
        }
    }

    fun shouldScan(deviceId: String, networkKey: String): Boolean {
        val entry = entries[deviceId] ?: return true
        if (entry.networkKey != networkKey) return true
        return now() >= entry.nextScanAtMs
    }

    fun recordFailure(deviceId: String, networkKey: String) {
        val previous = entries[deviceId]?.takeIf { it.networkKey == networkKey }
        val failures = (previous?.failures ?: 0) + 1
        var waitMs = baseMs
        repeat(failures - 1) { waitMs = (waitMs * 2).coerceAtMost(maxMs) }
        entries[deviceId] = Entry(networkKey, failures, now() + waitMs.coerceAtMost(maxMs))
        save()
    }

    /**
     * The peer answered over Tailscale or Iroh, so it needs no LAN scan for [holdMs]. Unlike [recordSuccess]
     * this keeps the failure count and never shortens a longer wait; only a LAN reach forgets it.
     */
    fun recordRemoteReach(deviceId: String, networkKey: String, holdMs: Long) {
        val previous = entries[deviceId]?.takeIf { it.networkKey == networkKey }
        val until = maxOf(previous?.nextScanAtMs ?: 0L, now() + holdMs)
        entries[deviceId] = Entry(networkKey, previous?.failures ?: 1, until)
        save()
    }

    fun recordSuccess(deviceId: String) {
        if (entries.remove(deviceId) != null) save()
    }

    @Synchronized
    private fun save() {
        val moment = now()
        persist(
            entries.entries
                .filter { it.value.nextScanAtMs > moment }
                .joinToString("\n") { (id, e) -> "$id\t${e.networkKey}\t${e.failures}\t${e.nextScanAtMs}" }
        )
    }

    companion object {
        /** The local /24s this device is on, so a scan failure on one LAN does not hold back scans on another. */
        fun currentNetworkKey(): String =
            NetworkUtils.lanBindCandidates()
                .filter { NetworkUtils.isUsableLanIpv4(it) }
                .map { it.trim().substringBeforeLast('.') }
                .distinct()
                .sorted()
                .joinToString(",")
    }
}
