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
        DeviceDetailsFieldId.Brightness, DeviceDetailsFieldId.Graphics -> DiagnosticGroup.Display
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
    /** The groups of every shown field. Fixed facts are asked every time too: the saved copy only fills the screen until the answer arrives. */
    fun toRequest(shown: List<DeviceDetailsFieldId>): Set<DiagnosticGroup> =
        shown.mapNotNull { it.group }.toSet()

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
