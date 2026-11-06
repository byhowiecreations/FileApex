package com.fileapex.data.identity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PeerDeviceNameLabel(
    val peerDeviceId: String,
    val peerDeviceName: String,
    val assignedName: String,
    val updatedAtEpochMs: Long
)

/** What the platform store held. [readable] is false when sealed labels exist but the key cannot open them yet. */
data class StoredPeerLabels(val labels: List<PeerDeviceNameLabel>, val readable: Boolean)

/**
 * Labels peers gave this device's name. Sealed on disk. A read made while the sealing key is not available yet is not
 * an empty list: the store stays unsettled and reads again on every call. A rename that arrives meanwhile is shown
 * and held, never written, and is merged with the stored labels (newest per peer) when they open, so a start-up race
 * cannot replace the stored labels with that one rename.
 */
object DeviceNamePeerLabelsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _labels = MutableStateFlow<List<PeerDeviceNameLabel>>(emptyList())
    val labels: StateFlow<List<PeerDeviceNameLabel>> = _labels.asStateFlow()

    private var settled = false
    private val held = mutableMapOf<String, PeerDeviceNameLabel>()

    @Volatile
    private var reader: () -> StoredPeerLabels = { readPeerDeviceNameLabels() }

    @Volatile
    private var writer: (List<PeerDeviceNameLabel>) -> Unit = { persistPeerDeviceNameLabels(it) }

    fun ensureLoaded() {
        synchronized(lock) { loadLocked() }
    }

    private fun loadLocked() {
        if (settled) return
        val stored = reader()
        if (!stored.readable) return
        val merged = merge(stored.labels, held.values)
        _labels.value = merged
        if (held.isNotEmpty()) writer(merged)
        held.clear()
        settled = true
    }

    fun record(peerDeviceId: String, peerDeviceName: String, assignedName: String, updatedAtEpochMs: Long) {
        val peerId = peerDeviceId.trim()
        val name = assignedName.trim()
        if (peerId.isEmpty() || name.isEmpty()) return
        synchronized(lock) {
            loadLocked()
            val entry = PeerDeviceNameLabel(
                peerDeviceId = peerId,
                peerDeviceName = peerDeviceName.trim().ifBlank { peerId },
                assignedName = name,
                updatedAtEpochMs = updatedAtEpochMs
            )
            val next = (_labels.value.filterNot { it.peerDeviceId == peerId } + entry).sortedByDescending { it.updatedAtEpochMs }
            _labels.value = next
            if (settled) writer(next) else held[peerId] = entry
        }
    }

    fun reloadFromDisk() {
        synchronized(lock) {
            val stored = reader()
            if (!stored.readable) return
            _labels.value = merge(stored.labels, held.values)
            settled = true
            held.clear()
        }
    }

    private fun merge(stored: List<PeerDeviceNameLabel>, pending: Collection<PeerDeviceNameLabel>): List<PeerDeviceNameLabel> =
        (stored + pending)
            .groupBy { it.peerDeviceId }
            .map { (_, same) -> same.maxBy { it.updatedAtEpochMs } }
            .sortedByDescending { it.updatedAtEpochMs }

    internal fun resetForTest(reader: () -> StoredPeerLabels, writer: (List<PeerDeviceNameLabel>) -> Unit) {
        synchronized(lock) {
            this.reader = reader
            this.writer = writer
            _labels.value = emptyList()
            held.clear()
            settled = false
        }
    }

    internal fun encode(labels: List<PeerDeviceNameLabel>): String = json.encodeToString(labels)

    internal fun decode(raw: String): List<PeerDeviceNameLabel> =
        runCatching {
            if (raw.isBlank()) emptyList()
            else json.decodeFromString<List<PeerDeviceNameLabel>>(raw)
        }.getOrDefault(emptyList())
}

internal expect fun readPeerDeviceNameLabels(): StoredPeerLabels
internal expect fun persistPeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>)
