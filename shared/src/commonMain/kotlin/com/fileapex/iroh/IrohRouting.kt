package com.fileapex.iroh

import com.fileapex.tailscale.TailscaleLoopbackDial
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object IrohBridgePorts {
    private val ports = ConcurrentHashMap.newKeySet<Int>()

    fun add(port: Int) {
        ports.add(port)
    }

    fun remove(port: Int) {
        ports.remove(port)
    }

    fun clear() = ports.clear()

    fun contains(port: Int): Boolean = port in ports

    fun anyActive(): Boolean = ports.isNotEmpty()
}

internal fun irohLoopbackOrNull(host: String, port: Int): TailscaleLoopbackDial? =
    if (host == "127.0.0.1" && IrohBridgePorts.contains(port)) TailscaleLoopbackDial("127.0.0.1", port, "") else null

fun isIrohRoute(host: String): Boolean = host == "127.0.0.1" && IrohBridgePorts.anyActive()

class IrohReachabilityCache(
    private val hitTtlMs: Long,
    private val missTtlMs: Long,
    private val now: () -> Long
) {
    private class Entry(val reachable: Boolean, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun check(key: String, probe: suspend () -> Boolean): Boolean {
        fresh(key)?.let { return it }
        return locks.getOrPut(key) { Mutex() }.withLock {
            fresh(key)?.let { return@withLock it }
            val reachable = probe()
            entries[key] = Entry(reachable, now() + if (reachable) hitTtlMs else missTtlMs)
            reachable
        }
    }

    fun forget(key: String) {
        entries.remove(key)
    }

    fun clear() = entries.clear()

    private fun fresh(key: String): Boolean? = entries[key]?.takeIf { it.expiresAt > now() }?.reachable
}

object IrohTransferProgress {
    private const val IROH_POLL_TIMEOUT_MS = 20_000L

    fun shown(confirmed: Long, alreadyOnReceiver: Long, sentByUs: Long, total: Long): Long {
        val local = (alreadyOnReceiver + sentByUs.coerceAtLeast(0L)).coerceAtMost(total)
        return maxOf(confirmed, local).coerceIn(0L, total)
    }

    fun pollTimeoutMs(host: String, port: Int, normalMs: Long): Long =
        if (irohLoopbackOrNull(host, port) != null) maxOf(normalMs, IROH_POLL_TIMEOUT_MS) else normalMs
}
