package com.fileapex.data.settings

import com.fileapex.iroh.IrohRelaySetting
import com.fileapex.iroh.IrohRelayUrl
import com.fileapex.iroh.IrohRelayUrlCheck
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The self-hosted Iroh relay choice: on or off, address, token, and when it last changed. */
class IrohRelaySettingsStore(private val store: SettingsKvStore) {
    private val enabledFlow = MutableStateFlow(storedEnabled())
    private val urlFlow = MutableStateFlow(store.getString(KEY_URL, ""))

    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()
    val url: StateFlow<String> = urlFlow.asStateFlow()

    fun token(): String = store.getString(BaseAppSettings.KEY_IROH_RELAY_TOKEN, "")

    fun current(): IrohRelaySetting = IrohRelaySetting(enabled.value, url.value, token(), store.getLong(KEY_CHANGED_AT, 0L))

    /** Reads the stored values again, after the store was cleared. */
    fun reload() {
        enabledFlow.value = storedEnabled()
        urlFlow.value = store.getString(KEY_URL, "")
    }

    /**
     * Saves the relay. A blank or invalid [url] saves nothing and turns the relay off; returns whether it is on.
     * Only a real change is stamped, so a repeated save never overrides a newer change from another device.
     */
    fun save(enabled: Boolean, url: String, token: String): Boolean {
        val before = current()
        val checked = IrohRelayUrl.check(url)
        if (checked is IrohRelayUrlCheck.Valid) {
            store.putString(KEY_URL, checked.url)
            urlFlow.value = checked.url
            store.putString(BaseAppSettings.KEY_IROH_RELAY_TOKEN, token.trim())
        }
        val on = enabled && checked is IrohRelayUrlCheck.Valid
        store.putBoolean(KEY_ENABLED, on)
        enabledFlow.value = on
        if (current().copy(changedAtMs = before.changedAtMs) != before) store.putLong(KEY_CHANGED_AT, TimeUtils.now())
        return on
    }

    /** Stores a choice that arrived from a paired device, keeping the sender's change time. */
    fun apply(setting: IrohRelaySetting) {
        // A cluster "off" carries no address, so the one saved here stays for next time.
        if (setting.url.isNotBlank()) {
            store.putString(KEY_URL, setting.url)
            urlFlow.value = setting.url
            store.putString(BaseAppSettings.KEY_IROH_RELAY_TOKEN, setting.token)
        }
        val on = setting.enabled && IrohRelayUrl.check(urlFlow.value) is IrohRelayUrlCheck.Valid
        store.putBoolean(KEY_ENABLED, on)
        enabledFlow.value = on
        store.putLong(KEY_CHANGED_AT, setting.changedAtMs)
    }

    private fun storedEnabled(): Boolean =
        store.getBoolean(KEY_ENABLED, false) && IrohRelayUrl.check(store.getString(KEY_URL, "")) is IrohRelayUrlCheck.Valid

    private companion object {
        const val KEY_ENABLED = "iroh_relay_enabled"
        const val KEY_URL = "iroh_relay_url"
        const val KEY_CHANGED_AT = "iroh_relay_changed_at"
    }
}
