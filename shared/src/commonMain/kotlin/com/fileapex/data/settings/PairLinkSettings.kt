package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hidden Simple Home link state: when the user last chose the connected device, the grant a computer gave this phone
 * (JSON) and the claim a phone made on this computer (JSON). Empty or 0 when there is none.
 */
class PairLinkSettings(private val store: SettingsKvStore) {
    private val chosenAtFlow = MutableStateFlow(store.getLong(KEY_CHOSEN_AT, 0L))
    val chosenAt: StateFlow<Long> = chosenAtFlow.asStateFlow()
    private val grantFlow = MutableStateFlow(store.getString(KEY_GRANT, ""))
    val grant: StateFlow<String> = grantFlow.asStateFlow()
    private val phoneClaimFlow = MutableStateFlow(store.getString(KEY_PHONE_CLAIM, ""))
    val phoneClaim: StateFlow<String> = phoneClaimFlow.asStateFlow()

    /** Remembers when [deviceId] was chosen with Switch device; an empty id clears the time. */
    fun markChosen(deviceId: String, nowEpochMs: Long) {
        val at = if (deviceId.isEmpty()) 0L else nowEpochMs
        chosenAtFlow.value = at
        store.putLong(KEY_CHOSEN_AT, at)
    }

    fun setGrant(json: String) {
        grantFlow.value = json
        store.putString(KEY_GRANT, json)
    }

    fun setPhoneClaim(json: String) {
        phoneClaimFlow.value = json
        store.putString(KEY_PHONE_CLAIM, json)
    }

    fun clear() {
        chosenAtFlow.value = 0L
        grantFlow.value = ""
        phoneClaimFlow.value = ""
    }

    private companion object {
        const val KEY_CHOSEN_AT = "simple_active_device_chosen_at"
        const val KEY_GRANT = "notification_companion_grant"
        const val KEY_PHONE_CLAIM = "phone_link_claim"
    }
}
