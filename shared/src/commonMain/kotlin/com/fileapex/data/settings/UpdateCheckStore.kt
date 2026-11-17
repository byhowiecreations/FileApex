package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether and how often to look for a new version, when it last happened and which version the user skipped. */
class UpdateCheckStore(private val store: SettingsKvStore) {
    private val enabledFlow = MutableStateFlow(store.getBoolean(KEY_ENABLED, false))
    private val promptShownFlow = MutableStateFlow(store.getBoolean(KEY_PROMPT_SHOWN, false))
    private val unitFlow = MutableStateFlow(UpdateCheckUnit.fromStorage(store.getString(KEY_UNIT, UpdateCheckUnit.Days.name)))
    private val amountFlow = MutableStateFlow(UpdateCheckFrequency.sanitizeAmount(unitFlow.value, store.getInt(KEY_AMOUNT, 1)))
    private val lastCheckFlow = MutableStateFlow(store.getLong(KEY_LAST_CHECK, 0L))
    private val skippedFlow = MutableStateFlow(store.getString(KEY_SKIPPED_VERSION, ""))

    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()
    val promptShown: StateFlow<Boolean> = promptShownFlow.asStateFlow()
    val intervalUnit: StateFlow<UpdateCheckUnit> = unitFlow.asStateFlow()
    val intervalAmount: StateFlow<Int> = amountFlow.asStateFlow()
    val lastCheckEpochMs: StateFlow<Long> = lastCheckFlow.asStateFlow()
    val skippedVersion: StateFlow<String> = skippedFlow.asStateFlow()

    fun setEnabled(value: Boolean) {
        store.putBoolean(KEY_ENABLED, value)
        enabledFlow.value = value
    }

    fun setPromptShown(value: Boolean) {
        promptShownFlow.value = value
        store.putBoolean(KEY_PROMPT_SHOWN, value)
    }

    fun setInterval(unit: UpdateCheckUnit, amount: Int) {
        val safeAmount = UpdateCheckFrequency.sanitizeAmount(unit, amount)
        store.putString(KEY_UNIT, unit.name)
        store.putInt(KEY_AMOUNT, safeAmount)
        unitFlow.value = unit
        amountFlow.value = safeAmount
    }

    fun setLastCheck(epochMs: Long) {
        store.putLong(KEY_LAST_CHECK, epochMs)
        lastCheckFlow.value = epochMs
    }

    fun setSkippedVersion(version: String) {
        val cleaned = version.trim()
        store.putString(KEY_SKIPPED_VERSION, cleaned)
        skippedFlow.value = cleaned
    }

    /** The settings store has been cleared; bring the in-memory state back to the defaults. */
    fun forgetInMemory() {
        enabledFlow.value = false
        promptShownFlow.value = false
        unitFlow.value = UpdateCheckUnit.Days
        amountFlow.value = 1
        lastCheckFlow.value = 0L
        skippedFlow.value = ""
    }

    private companion object {
        const val KEY_ENABLED = BaseAppSettings.KEY_CHECK_FOR_UPDATES
        const val KEY_PROMPT_SHOWN = BaseAppSettings.KEY_UPDATE_CHECK_PROMPT_SHOWN
        const val KEY_UNIT = BaseAppSettings.KEY_UPDATE_UNIT
        const val KEY_AMOUNT = BaseAppSettings.KEY_UPDATE_AMOUNT
        const val KEY_LAST_CHECK = BaseAppSettings.KEY_LAST_UPDATE_CHECK
        const val KEY_SKIPPED_VERSION = BaseAppSettings.KEY_SKIPPED_UPDATE_VERSION
    }
}
