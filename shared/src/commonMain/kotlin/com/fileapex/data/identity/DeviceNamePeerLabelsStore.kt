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

object DeviceNamePeerLabelsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _labels = MutableStateFlow<List<PeerDeviceNameLabel>>(emptyList())
    val labels: StateFlow<List<PeerDeviceNameLabel>> = _labels.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) return
            _labels.value = readPeerDeviceNameLabels()
            loaded = true
        }
    }

    fun record(peerDeviceId: String, peerDeviceName: String, assignedName: String, updatedAtEpochMs: Long) {
        val peerId = peerDeviceId.trim()
        val name = assignedName.trim()
        if (peerId.isEmpty() || name.isEmpty()) return
        synchronized(lock) {
            ensureLoaded()
            val peerLabel = peerDeviceName.trim().ifBlank { peerId }
            val next = _labels.value
                .filterNot { it.peerDeviceId == peerId } +
                PeerDeviceNameLabel(
                    peerDeviceId = peerId,
                    peerDeviceName = peerLabel,
                    assignedName = name,
                    updatedAtEpochMs = updatedAtEpochMs
                )
            _labels.value = next.sortedByDescending { it.updatedAtEpochMs }
            persistPeerDeviceNameLabels(next)
        }
    }

    fun reloadFromDisk() {
        synchronized(lock) {
            loaded = true
            _labels.value = readPeerDeviceNameLabels()
        }
    }

    internal fun encode(labels: List<PeerDeviceNameLabel>): String = json.encodeToString(labels)

    internal fun decode(raw: String): List<PeerDeviceNameLabel> =
        runCatching {
            if (raw.isBlank()) emptyList()
            else json.decodeFromString<List<PeerDeviceNameLabel>>(raw)
        }.getOrDefault(emptyList())
}

internal expect fun readPeerDeviceNameLabels(): List<PeerDeviceNameLabel>
internal expect fun persistPeerDeviceNameLabels(labels: List<PeerDeviceNameLabel>)
