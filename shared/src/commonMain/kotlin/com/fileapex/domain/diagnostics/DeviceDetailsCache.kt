package com.fileapex.domain.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class CachedDeviceDetails(val deviceId: String, val snapshot: PeerDeviceDiagnostics)

/**
 * The last device details received from each paired device, kept on this device. What never changes (make, model,
 * kernel, chip, core count, screen, memory and storage size) is kept when a later answer leaves it out; the changing
 * values (battery, free space, network, uptime) always come from the newest answer. An entry goes when its device
 * is no longer in the roster.
 */
object DeviceDetailsCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var entries: Map<String, CachedDeviceDetails>? = null

    fun get(deviceId: String): PeerDeviceDiagnostics? = synchronized(lock) { loaded()[deviceId]?.snapshot }

    /** Stores [fresh] merged over what is already known and returns what to show. */
    fun remember(deviceId: String, fresh: PeerDeviceDiagnostics): PeerDeviceDiagnostics = synchronized(lock) {
        val merged = merge(loaded()[deviceId]?.snapshot, fresh)
        save(loaded() + (deviceId to CachedDeviceDetails(deviceId, merged)))
        merged
    }

    fun prune(activeIds: Set<String>) = synchronized(lock) {
        val kept = loaded().filterKeys { it in activeIds }
        if (kept.size != loaded().size) save(kept)
    }

    private fun loaded(): Map<String, CachedDeviceDetails> =
        entries ?: decode(readDeviceDetailsCacheRaw()).associateBy { it.deviceId }.also { entries = it }

    private fun save(next: Map<String, CachedDeviceDetails>) {
        entries = next
        writeDeviceDetailsCacheRaw(encode(next.values.toList()))
    }

    internal fun encode(list: List<CachedDeviceDetails>): String = json.encodeToString(ListSerializer(CachedDeviceDetails.serializer()), list)

    internal fun decode(raw: String): List<CachedDeviceDetails> = runCatching {
        if (raw.isBlank()) emptyList() else json.decodeFromString(ListSerializer(CachedDeviceDetails.serializer()), raw)
    }.getOrDefault(emptyList())

    internal fun merge(previous: PeerDeviceDiagnostics?, fresh: PeerDeviceDiagnostics): PeerDeviceDiagnostics {
        if (previous == null) return fresh
        fun keep(new: String, old: String) = new.ifBlank { old }
        fun <T> keep(new: T?, old: T?) = new ?: old
        return fresh.copy(
            platform = keep(fresh.platform, previous.platform),
            device = fresh.device.copy(
                make = keep(fresh.device.make, previous.device.make),
                model = keep(fresh.device.model, previous.device.model),
                kernelVersion = keep(fresh.device.kernelVersion, previous.device.kernelVersion),
                osBuildVersion = keep(fresh.device.osBuildVersion, previous.device.osBuildVersion)
            ),
            processor = fresh.processor.copy(
                architecture = keep(fresh.processor.architecture, previous.processor.architecture),
                hardware = keep(fresh.processor.hardware, previous.processor.hardware),
                totalCoreCount = keep(fresh.processor.totalCoreCount, previous.processor.totalCoreCount)
            ),
            display = fresh.display.copy(resolution = keep(fresh.display.resolution, previous.display.resolution)),
            storage = fresh.storage.copy(totalBytes = keep(fresh.storage.totalBytes, previous.storage.totalBytes)),
            memory = fresh.memory.copy(totalBytes = keep(fresh.memory.totalBytes, previous.memory.totalBytes))
        )
    }
}

internal expect fun readDeviceDetailsCacheRaw(): String
internal expect fun writeDeviceDetailsCacheRaw(raw: String)
