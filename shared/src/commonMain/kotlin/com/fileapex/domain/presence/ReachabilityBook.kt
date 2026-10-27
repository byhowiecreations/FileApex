package com.fileapex.domain.presence

import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

internal class ReachabilityBook {
    private val lock = Mutex()
    private val lastReachableEpochById = mutableMapOf<String, Long>()
    private val _epochMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _onlineIds = MutableStateFlow<Set<String>>(emptySet())
    private val _snapshotEpochMs = MutableStateFlow(0L)
    private val _via = MutableStateFlow<Map<String, RouteKind>>(emptyMap())

    val epochMs: StateFlow<Map<String, Long>> = _epochMs.asStateFlow()
    val onlineIds: StateFlow<Set<String>> = _onlineIds.asStateFlow()
    val snapshotEpochMs: StateFlow<Long> = _snapshotEpochMs.asStateFlow()

    /** The route the last successful contact with each peer used; only changes when the route does. */
    val via: StateFlow<Map<String, RouteKind>> = _via.asStateFlow()

    fun noteRoute(deviceId: String, kind: RouteKind) {
        _via.update { if (it[deviceId] == kind) it else it + (deviceId to kind) }
    }

    suspend fun record(deviceIds: Array<out String>, epochMs: Long, via: RouteKind? = null) {
        lock.withLock {
            var changed = false
            for (id in deviceIds) {
                val trimmed = id.trim()
                if (trimmed.isEmpty()) continue
                if (via != null) noteRoute(trimmed, via)
                val previous = lastReachableEpochById[trimmed] ?: 0L
                val next = epochMs.coerceAtLeast(previous)
                if (next > previous) {
                    lastReachableEpochById[trimmed] = next
                    changed = true
                }
            }
            if (changed) {
                _epochMs.value = lastReachableEpochById.toMap()
                val currentIds = _onlineIds.value
                // An old timestamp must not put a device in the online set.
                val toAdd = deviceIds.map { it.trim() }.filter { it.isNotEmpty() && !currentIds.contains(it) }
                    .takeIf { TimeUtils.isWithinWindow(epochMs, LanPresenceTiming.PRESENCE_READY_THRESHOLD_MS) }
                    .orEmpty()
                if (toAdd.isNotEmpty()) {
                    _onlineIds.value = currentIds + toAdd
                }
            }
        }
    }

    suspend fun publishOnline(nextOnline: Set<String>) {
        lock.withLock {
            if (_onlineIds.value != nextOnline) {
                _onlineIds.value = nextOnline
            }
        }
    }

    fun markSnapshot() {
        _snapshotEpochMs.value = TimeUtils.now()
    }

    fun clearSnapshot() {
        _onlineIds.value = emptySet()
        _snapshotEpochMs.value = 0L
    }

    fun wasRecentlyReachable(deviceId: String, withinMs: Long): Boolean {
        val epoch = _epochMs.value[deviceId] ?: return false
        return TimeUtils.isWithinWindow(epoch, withinMs)
    }
}
