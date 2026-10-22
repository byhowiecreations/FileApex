package com.fileapex.domain.peer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A Docker node keeps the fixed client version "docker" (older apps rely on it to tell it apart) and sends its real
 * app version in the legacy `appVersion` field. That version is kept in memory for the device card and refreshed by every
 * direct identity answer.
 */
object PeerAppVersions {
    private val internal = MutableStateFlow<Map<String, String>>(emptyMap())
    val versions: StateFlow<Map<String, String>> = internal.asStateFlow()

    fun record(state: PeerNodeState) {
        val version = state.appVersion.trim()
        if (version.isEmpty() || !state.clientVersion.trim().equals("docker", ignoreCase = true)) return
        internal.update { if (it[state.deviceId] == version) it else it + (state.deviceId to version) }
    }
}
