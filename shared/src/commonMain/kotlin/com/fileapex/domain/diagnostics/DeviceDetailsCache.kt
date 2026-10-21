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

    /**
     * Stores [fresh] merged over what is already known and returns what to show. [asked] is the groups the peer was
     * asked for (null: all); a group it was not asked for keeps its saved values.
     */
    fun remember(deviceId: String, fresh: PeerDeviceDiagnostics, asked: Set<DiagnosticGroup>? = null): PeerDeviceDiagnostics = synchronized(lock) {
        val previous = loaded()[deviceId]?.snapshot
        val merged = merge(previous, keepUnasked(previous, fresh, asked))
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

    internal fun keepUnasked(previous: PeerDeviceDiagnostics?, fresh: PeerDeviceDiagnostics, asked: Set<DiagnosticGroup>?): PeerDeviceDiagnostics {
        if (previous == null || asked == null) return fresh
        fun <T> pick(group: DiagnosticGroup, new: T, old: T) = if (group in asked) new else old
        return fresh.copy(
            device = pick(DiagnosticGroup.Identity, fresh.device, previous.device),
            processor = pick(DiagnosticGroup.Processor, fresh.processor, previous.processor),
            display = pick(DiagnosticGroup.Display, fresh.display, previous.display),
            battery = pick(DiagnosticGroup.Battery, fresh.battery, previous.battery),
            storage = pick(DiagnosticGroup.Storage, fresh.storage, previous.storage),
            network = pick(DiagnosticGroup.Network, fresh.network, previous.network),
            uptime = pick(DiagnosticGroup.Uptime, fresh.uptime, previous.uptime),
            thermal = pick(DiagnosticGroup.Thermal, fresh.thermal, previous.thermal),
            memory = pick(DiagnosticGroup.Memory, fresh.memory, previous.memory)
        )
    }

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
