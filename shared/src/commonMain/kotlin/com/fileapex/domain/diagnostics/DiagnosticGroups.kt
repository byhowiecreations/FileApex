package com.fileapex.domain.diagnostics

/** The parts of a device snapshot a peer can be asked for one at a time, so it only measures what is shown. */
enum class DiagnosticGroup { Identity, Processor, Display, Battery, Storage, Network, Uptime, Thermal, Memory }

val DeviceDetailsFieldId.group: DiagnosticGroup?
    get() = when (this) {
        DeviceDetailsFieldId.Platform, DeviceDetailsFieldId.Collected, DeviceDetailsFieldId.AppVersion -> null
        DeviceDetailsFieldId.Make, DeviceDetailsFieldId.Model, DeviceDetailsFieldId.Kernel,
        DeviceDetailsFieldId.OsBuild -> DiagnosticGroup.Identity
        DeviceDetailsFieldId.Processor, DeviceDetailsFieldId.CpuArchitecture, DeviceDetailsFieldId.CpuHardware,
        DeviceDetailsFieldId.CpuCores, DeviceDetailsFieldId.CpuFrequency -> DiagnosticGroup.Processor
        DeviceDetailsFieldId.DisplayResolution, DeviceDetailsFieldId.RefreshRate,
        DeviceDetailsFieldId.Brightness -> DiagnosticGroup.Display
        DeviceDetailsFieldId.BatteryLevel, DeviceDetailsFieldId.Charging,
        DeviceDetailsFieldId.BatteryTemp -> DiagnosticGroup.Battery
        DeviceDetailsFieldId.Storage -> DiagnosticGroup.Storage
        DeviceDetailsFieldId.NetworkType, DeviceDetailsFieldId.Ssid, DeviceDetailsFieldId.Signal,
        DeviceDetailsFieldId.LinkSpeed, DeviceDetailsFieldId.FrequencyBand, DeviceDetailsFieldId.Channel,
        DeviceDetailsFieldId.CellularGeneration, DeviceDetailsFieldId.CellularCarrier,
        DeviceDetailsFieldId.CellularSignal, DeviceDetailsFieldId.CellularFrequency,
        DeviceDetailsFieldId.CellularBand -> DiagnosticGroup.Network
        DeviceDetailsFieldId.Uptime, DeviceDetailsFieldId.LastBoot -> DiagnosticGroup.Uptime
        DeviceDetailsFieldId.ThermalState, DeviceDetailsFieldId.ThermalTemp -> DiagnosticGroup.Thermal
        DeviceDetailsFieldId.Memory -> DiagnosticGroup.Memory
    }

object DiagnosticGroups {
    /**
     * The groups worth asking a peer for: those holding a shown field that changes, plus a shown fixed field
     * the saved copy has no value for yet.
     */
    fun toRequest(shown: List<DeviceDetailsFieldId>, saved: PeerDeviceDiagnostics?): Set<DiagnosticGroup> =
        shown.filter { field -> field.group != null && (field.changesOverTime || saved == null || !hasFixedValue(field, saved)) }
            .mapNotNull { it.group }
            .toSet()

    private fun hasFixedValue(field: DeviceDetailsFieldId, saved: PeerDeviceDiagnostics): Boolean = when (field) {
        DeviceDetailsFieldId.Make -> saved.device.make.isNotBlank()
        DeviceDetailsFieldId.Model -> saved.device.model.isNotBlank()
        DeviceDetailsFieldId.Kernel -> saved.device.kernelVersion.isNotBlank()
        DeviceDetailsFieldId.OsBuild -> saved.device.osBuildVersion.isNotBlank()
        DeviceDetailsFieldId.Processor -> saved.processor.hardware.isNotBlank() || saved.processor.architecture.isNotBlank()
        DeviceDetailsFieldId.CpuArchitecture -> saved.processor.architecture.isNotBlank()
        DeviceDetailsFieldId.CpuHardware -> saved.processor.hardware.isNotBlank()
        DeviceDetailsFieldId.DisplayResolution -> saved.display.resolution.isNotBlank()
        else -> false
    }

    fun queryParams(summaryOnly: Boolean, groups: Set<DiagnosticGroup>?): Map<String, String> = when {
        summaryOnly -> mapOf("detail" to "summary")
        groups != null -> mapOf("groups" to encode(groups))
        else -> emptyMap()
    }

    fun encode(groups: Set<DiagnosticGroup>): String =
        if (groups.isEmpty()) NONE else groups.joinToString(",") { it.name }

    /** Null means "everything": no list was sent, which is also what a peer that predates the list does. */
    fun decode(raw: String?): Set<DiagnosticGroup>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text == NONE) return emptySet()
        return text.split(',').mapNotNull { name -> DiagnosticGroup.entries.firstOrNull { it.name == name.trim() } }.toSet()
    }

    private const val NONE = "none"
}
