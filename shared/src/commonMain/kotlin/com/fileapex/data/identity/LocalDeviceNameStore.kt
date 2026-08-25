package com.fileapex.data.identity

import com.fileapex.i18n.AppI18n
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live local display name. Persistence stays in [updateLocalDeviceName];
 * this bus keeps UI (Devices "This device") in sync when peers rename us.
 */
object LocalDeviceNameStore {
    private val _deviceName = MutableStateFlow("")
    private val lock = Any()

    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    fun current(): String {
        ensureLoaded()
        return _deviceName.value
    }

    fun ensureLoaded() {
        synchronized(lock) {
            if (_deviceName.value.isNotEmpty()) return
            _deviceName.value = loadLocalIdentity().deviceName
        }
    }

    /** Persist and publish a new local display name. */
    fun apply(newName: String) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { AppI18n.t("device_name_empty") }
        updateLocalDeviceName(trimmed)
        _deviceName.value = trimmed
    }

    fun reloadFromDisk() {
        _deviceName.value = loadLocalIdentity().deviceName
    }
}
