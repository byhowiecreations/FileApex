package com.fileapex.domain.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Identifies a row in the Device Details popup. Order and visibility are user-configurable
 * via Settings → Device Details.
 */
enum class DeviceDetailsFieldId(val label: String) {
    Platform("Platform"),
    Collected("Collected"),
    Make("Make"),
    Model("Model"),
    AppVersion("App version"),
    Kernel("Kernel"),
    OsBuild("OS build"),
    Processor("Processor"),
    CpuArchitecture("CPU architecture"),
    CpuHardware("CPU hardware"),
    CpuCores("CPU cores"),
    CpuFrequency("CPU frequency"),
    DisplayResolution("Display resolution"),
    RefreshRate("Refresh rate"),
    Brightness("Brightness"),
    BatteryLevel("Battery level"),
    Charging("Charging"),
    BatteryTemp("Battery temp"),
    Storage("Storage"),
    NetworkType("Network type"),
    Ssid("SSID"),
    Signal("Signal"),
    LinkSpeed("Link speed"),
    FrequencyBand("Frequency band"),
    Channel("Channel"),
    CellularGeneration("Network generation"),
    CellularCarrier("Carrier"),
    CellularSignal("Signal strength"),
    CellularFrequency("Frequency"),
    CellularBand("Cell band"),
    Uptime("Uptime"),
    LastBoot("Last boot"),
    ThermalState("Thermal state"),
    ThermalTemp("Thermal temp"),
    Memory("Memory");

    /** Shown by default and listed first; everything else is advanced and off until the user turns it on. */
    val isBasic: Boolean
        get() = this in BASIC

    /** Wi-Fi-only rows, hidden when the peer is on cellular or ethernet. */
    val wifiOnly: Boolean
        get() = this in WIFI_ONLY

    /** Cellular-only rows, hidden when the peer is on Wi-Fi or ethernet. */
    val cellularOnly: Boolean
        get() = this in CELLULAR_ONLY

    /** False for facts that stay the same between answers; those keep their saved value and show no "updating" marker. */
    val changesOverTime: Boolean
        get() = this !in FIXED

    companion object {
        private val FIXED = setOf(
            Platform, Make, Model, Kernel, OsBuild, Processor, CpuArchitecture, CpuHardware, DisplayResolution
        )
        private val BASIC = listOf(
            Platform, Make, Model, AppVersion, OsBuild, Kernel,
            BatteryLevel, Storage, Memory, CpuArchitecture, CpuHardware
        )
        private val WIFI_ONLY = setOf(Ssid, Signal, LinkSpeed, FrequencyBand, Channel)
        private val CELLULAR_ONLY = setOf(
            CellularGeneration,
            CellularCarrier,
            CellularSignal,
            CellularFrequency,
            CellularBand
        )

        val basicFields: List<DeviceDetailsFieldId> = BASIC

        val advancedFields: List<DeviceDetailsFieldId> = entries.filterNot { it in BASIC }

        val defaultOrder: List<DeviceDetailsFieldId> = BASIC + advancedFields
    }
}

@Serializable
data class DeviceDetailsFieldPreference(
    val id: String,
    val visible: Boolean = true
)

@Serializable
data class DeviceDetailsDisplayPreferences(
    val fields: List<DeviceDetailsFieldPreference> = emptyList()
) {
    fun normalized(): DeviceDetailsDisplayPreferences {
        val storedById = fields.associate { it.id to it.visible }
        val ordered = buildList {
            fields.mapNotNull { pref ->
                DeviceDetailsFieldId.entries.find { it.name == pref.id }
            }.distinct().forEach { add(it) }
            DeviceDetailsFieldId.defaultOrder.forEach { id ->
                if (id !in this) add(id)
            }
        }
        return DeviceDetailsDisplayPreferences(
            fields = ordered.map { id ->
                DeviceDetailsFieldPreference(
                    id = id.name,
                    // A field this user has no setting for yet (new install, or one added later) follows its default.
                    visible = storedById[id.name] ?: id.isBasic
                )
            }
        )
    }

    fun visibleFieldIds(): List<DeviceDetailsFieldId> =
        normalized().fields.filter { it.visible }.mapNotNull { pref ->
            DeviceDetailsFieldId.entries.find { it.name == pref.id }
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun defaults(): DeviceDetailsDisplayPreferences =
            DeviceDetailsDisplayPreferences(
                fields = DeviceDetailsFieldId.defaultOrder.map { id ->
                    DeviceDetailsFieldPreference(id = id.name, visible = id.isBasic)
                }
            )

        fun decode(raw: String): DeviceDetailsDisplayPreferences {
            if (raw.isBlank()) return defaults()
            return runCatching {
                json.decodeFromString<DeviceDetailsDisplayPreferences>(raw).normalized()
            }.getOrDefault(defaults())
        }

        fun encode(preferences: DeviceDetailsDisplayPreferences): String =
            json.encodeToString(DeviceDetailsDisplayPreferences.serializer(), preferences.normalized())
    }
}
