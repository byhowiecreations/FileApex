package com.fileapex.widget

import android.content.Context
import android.content.SharedPreferences
import com.fileapex.data.seal.SealedStrings
import com.fileapex.platform.PrefsStringSlots
import com.fileapex.domain.widget.CachedBatteryReading
import com.fileapex.domain.widget.WidgetActionCatalog
import com.fileapex.domain.widget.WidgetConfig
import com.fileapex.domain.widget.WidgetKind
import com.fileapex.domain.widget.WidgetTargetBinding
import com.fileapex.domain.widget.WidgetTargetBindingCodec

/**
 * Stores per-widget layout, action slots, device bindings, and last user-fetched battery readings. Every value is
 * sealed with [SealedStrings]; the key names (widget ids and device ids) are not secret. A plain value or a number
 * left by an older version is read and sealed in place. With no key available nothing is written and reads are empty.
 */
class WidgetConfigStore(
    context: Context,
    prefsName: String = DEFAULT_PREFS_NAME
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val sealed = SealedStrings(PrefsStringSlots(prefs), PURPOSE)

    fun save(config: WidgetConfig) {
        val normalized = config.normalizedActions()
        val written = sealed.put(keyKind(config.appWidgetId), config.kind.wireId) &&
            sealed.put(keyBinding(config.appWidgetId), WidgetTargetBindingCodec.encode(config.binding)) &&
            sealed.put(keyActions(config.appWidgetId), normalized.joinToString(",") { it.wireId })
        if (!written) println("WidgetConfigStore: no key available yet, a widget configuration was not saved")
    }

    fun load(appWidgetId: Int): WidgetConfig? {
        val kind = WidgetKind.fromWireId(sealed.get(keyKind(appWidgetId))) ?: return null
        val binding = WidgetTargetBindingCodec.decode(sealed.get(keyBinding(appWidgetId)))
        val actionsRaw = sealed.get(keyActions(appWidgetId)).orEmpty()
        val parsedActions = if (actionsRaw.isBlank()) {
            emptyList()
        } else {
            actionsRaw.split(",").mapNotNull { WidgetActionCatalog.byWireId(it) }
        }
        return WidgetConfig(
            appWidgetId = appWidgetId,
            kind = kind,
            binding = binding,
            actions = parsedActions
        )
    }

    fun hasConfig(appWidgetId: Int): Boolean =
        WidgetKind.fromWireId(sealed.get(keyKind(appWidgetId))) != null

    fun listAppWidgetIds(): List<Int> {
        val ids = LinkedHashSet<Int>()
        for (key in prefs.all.keys) {
            if (!key.startsWith(PREFIX_CFG) || !key.endsWith(SUFFIX_KIND)) continue
            val id = key.removePrefix(PREFIX_CFG).removeSuffix(SUFFIX_KIND).toIntOrNull()
            if (id != null) ids += id
        }
        return ids.toList()
    }

    fun remove(appWidgetId: Int) {
        sealed.remove(keyKind(appWidgetId))
        sealed.remove(keyBinding(appWidgetId))
        sealed.remove(keyActions(appWidgetId))
    }

    fun saveBatteryReading(deviceId: String, reading: CachedBatteryReading) {
        val cleanId = deviceId.trim()
        if (cleanId.isEmpty() || reading.levelPercent !in 0..100) return
        val written = sealed.put(keyBatteryPct(cleanId), reading.levelPercent.toString()) &&
            sealed.put(keyBatteryTs(cleanId), reading.recordedAtEpochMs.toString()) &&
            sealed.put(keyBatteryCharging(cleanId), reading.chargingState)
        if (!written) println("WidgetConfigStore: no key available yet, a battery reading was not saved")
    }

    fun loadBatteryReading(deviceId: String): CachedBatteryReading? {
        val cleanId = deviceId.trim()
        if (cleanId.isEmpty()) return null
        val pct = sealed.get(keyBatteryPct(cleanId))?.toIntOrNull() ?: return null
        val ts = sealed.get(keyBatteryTs(cleanId))?.toLongOrNull() ?: return null
        if (pct !in 0..100 || ts <= 0L) return null
        return CachedBatteryReading(levelPercent = pct, recordedAtEpochMs = ts, chargingState = sealed.get(keyBatteryCharging(cleanId)).orEmpty())
    }

    fun retainPairedDevices(activeDeviceIds: Set<String>) {
        for (appWidgetId in listAppWidgetIds()) {
            val binding = load(appWidgetId)?.binding
            if (binding is WidgetTargetBinding.SpecificDevice && binding.deviceId !in activeDeviceIds) {
                sealed.put(keyBinding(appWidgetId), WidgetTargetBindingCodec.encode(WidgetTargetBinding.AskEachTime))
            }
        }
        val staleBatteryDevices = prefs.all.keys
            .filter { it.startsWith(PREFIX_BATTERY) && it.endsWith(SUFFIX_BAT_PCT) }
            .map { it.removePrefix(PREFIX_BATTERY).removeSuffix(SUFFIX_BAT_PCT) }
            .filter { it !in activeDeviceIds }
        for (deviceId in staleBatteryDevices) {
            sealed.remove(keyBatteryPct(deviceId))
            sealed.remove(keyBatteryTs(deviceId))
            sealed.remove(keyBatteryCharging(deviceId))
        }
    }

    private fun keyKind(id: Int) = "$PREFIX_CFG$id$SUFFIX_KIND"
    private fun keyBinding(id: Int) = "$PREFIX_CFG$id$SUFFIX_BINDING"
    private fun keyActions(id: Int) = "$PREFIX_CFG$id$SUFFIX_ACTIONS"
    private fun keyBatteryPct(deviceId: String) = "$PREFIX_BATTERY$deviceId$SUFFIX_BAT_PCT"
    private fun keyBatteryTs(deviceId: String) = "$PREFIX_BATTERY$deviceId$SUFFIX_BAT_TS"
    private fun keyBatteryCharging(deviceId: String) = "$PREFIX_BATTERY$deviceId$SUFFIX_BAT_CHG"

    companion object {
        const val DEFAULT_PREFS_NAME = "fileapex_widgets"
        private const val PURPOSE = "widgets"
        private const val PREFIX_CFG = "cfg_"
        private const val SUFFIX_KIND = "_kind"
        private const val SUFFIX_BINDING = "_bind"
        private const val SUFFIX_ACTIONS = "_acts"
        private const val PREFIX_BATTERY = "bat_"
        private const val SUFFIX_BAT_PCT = "_pct"
        private const val SUFFIX_BAT_TS = "_ts"
        private const val SUFFIX_BAT_CHG = "_chg"
    }
}
