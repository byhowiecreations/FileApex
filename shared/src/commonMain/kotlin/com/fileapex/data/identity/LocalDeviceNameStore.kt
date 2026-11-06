package com.fileapex.data.identity

import com.fileapex.i18n.AppI18n
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live local display name. Persistence stays in [updateLocalDeviceName];
 * this bus keeps UI (Devices "This device") in sync when peers rename us.
 *
 * A name read while its sealing key is not available yet is only the factory fallback. It is shown but never kept: every
 * call reads again until the real name opens, so a start-up race cannot leave the device announcing the factory name
 * for the rest of the run.
 */
object LocalDeviceNameStore {
    private val _deviceName = MutableStateFlow("")
    private val lock = Any()
    private var settled = false

    /** The identity lookup; tests replace it. */
    @Volatile
    private var loader: () -> LocalIdentity = { loadLocalIdentity() }

    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    fun current(): String {
        ensureLoaded()
        return _deviceName.value
    }

    fun ensureLoaded() {
        synchronized(lock) {
            if (settled) return
            val identity = loader()
            _deviceName.value = identity.deviceName
            settled = identity.deviceNameReadable
        }
    }

    /** Persist and publish a new local display name. */
    fun apply(newName: String) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { AppI18n.t("device_name_empty") }
        updateLocalDeviceName(trimmed)
        settleWith(trimmed)
    }

    internal fun settleWith(name: String) {
        synchronized(lock) {
            _deviceName.value = name
            settled = true
        }
    }

    fun reloadFromDisk() {
        synchronized(lock) {
            val identity = loader()
            _deviceName.value = identity.deviceName
            settled = identity.deviceNameReadable
        }
    }

    internal fun resetForTest(newLoader: () -> LocalIdentity) {
        synchronized(lock) {
            loader = newLoader
            _deviceName.value = ""
            settled = false
        }
    }
}
