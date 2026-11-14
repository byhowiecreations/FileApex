package com.fileapex.widget

import android.content.Context
import android.content.SharedPreferences
import com.fileapex.domain.widget.CachedBatteryReading
import com.fileapex.domain.widget.WidgetActionCatalog
import com.fileapex.domain.widget.WidgetConfig
import com.fileapex.domain.widget.WidgetKind
import com.fileapex.domain.widget.WidgetTargetBinding
import com.fileapex.domain.widget.WidgetTargetBindingCodec

/**
 * Stores per-widget layout, action slots, device bindings, and last user-fetched battery readings.
 */
class WidgetConfigStore(
    context: Context,
    prefsName: String = DEFAULT_PREFS_NAME
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun save(config: WidgetConfig) {
        val normalized = config.normalizedActions()
        prefs.edit()
            .putString(keyKind(config.appWidgetId), config.kind.wireId)
            .putString(keyBinding(config.appWidgetId), WidgetTargetBindingCodec.encode(config.binding))
            .putString(keyActions(config.appWidgetId), normalized.joinToString(",") { it.wireId })
            .apply()
    }

    fun load(appWidgetId: Int): WidgetConfig? {
        val kind = WidgetKind.fromWireId(prefs.getString(keyKind(appWidgetId), null)) ?: return null
        val binding = WidgetTargetBindingCodec.decode(prefs.getString(keyBinding(appWidgetId), null))
        val actionsRaw = prefs.getString(keyActions(appWidgetId), null).orEmpty()
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
        WidgetKind.fromWireId(prefs.getString(keyKind(appWidgetId), null)) != null

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
        prefs.edit()
            .remove(keyKind(appWidgetId))
            .remove(keyBinding(appWidgetId))
            .remove(keyActions(appWidgetId))
            .apply()
    }

    fun saveBatteryReading(deviceId: String, reading: CachedBatteryReading) {
        val cleanId = deviceId.trim()
        if (cleanId.isEmpty() || reading.levelPercent !in 0..100) return
        prefs.edit()
            .putInt(keyBatteryPct(cleanId), reading.levelPercent)
            .putLong(keyBatteryTs(cleanId), reading.recordedAtEpochMs)
            .putString(keyBatteryCharging(cleanId), reading.chargingState)
            .apply()
    }

    fun loadBatteryReading(deviceId: String): CachedBatteryReading? {
        val cleanId = deviceId.trim()
        if (cleanId.isEmpty() || !prefs.contains(keyBatteryPct(cleanId))) return null
        val pct = prefs.getInt(keyBatteryPct(cleanId), -1)
        val ts = prefs.getLong(keyBatteryTs(cleanId), 0L)
        if (pct !in 0..100 || ts <= 0L) return null
        val charging = prefs.getString(keyBatteryCharging(cleanId), "").orEmpty()
        return CachedBatteryReading(levelPercent = pct, recordedAtEpochMs = ts, chargingState = charging)
    }

    fun retainPairedDevices(activeDeviceIds: Set<String>) {
        val editor = prefs.edit()
        var dirty = false
        for (appWidgetId in listAppWidgetIds()) {
            val cfg = load(appWidgetId) ?: continue
            val binding = cfg.binding
            if (binding is WidgetTargetBinding.SpecificDevice && binding.deviceId !in activeDeviceIds) {
                editor.putString(
                    keyBinding(appWidgetId),
                    WidgetTargetBindingCodec.encode(WidgetTargetBinding.AskEachTime)
                )
                dirty = true
            }
        }
        val staleBatteryDevices = prefs.all.keys
            .filter { it.startsWith(PREFIX_BATTERY) && it.endsWith(SUFFIX_BAT_PCT) }
            .map { it.removePrefix(PREFIX_BATTERY).removeSuffix(SUFFIX_BAT_PCT) }
            .filter { it !in activeDeviceIds }
        for (deviceId in staleBatteryDevices) {
            editor.remove(keyBatteryPct(deviceId))
            editor.remove(keyBatteryTs(deviceId))
            editor.remove(keyBatteryCharging(deviceId))
            dirty = true
        }
        if (dirty) editor.apply()
    }

    private fun keyKind(id: Int) = "$PREFIX_CFG$id$SUFFIX_KIND"
    private fun keyBinding(id: Int) = "$PREFIX_CFG$id$SUFFIX_BINDING"
    private fun keyActions(id: Int) = "$PREFIX_CFG$id$SUFFIX_ACTIONS"
    private fun keyBatteryPct(deviceId: String) = "$PREFIX_BATTERY$deviceId$SUFFIX_BAT_PCT"
    private fun keyBatteryTs(deviceId: String) = "$PREFIX_BATTERY$deviceId$SUFFIX_BAT_TS"
    private fun keyBatteryCharging(deviceId: String) = "$PREFIX_BATTERY$deviceId$SUFFIX_BAT_CHG"

    companion object {
        const val DEFAULT_PREFS_NAME = "fileapex_widgets"
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
