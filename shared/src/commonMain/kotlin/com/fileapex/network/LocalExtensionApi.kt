package com.fileapex.network

import kotlinx.serialization.Serializable

/** A device as the Finder extensions list it: no address, folder or key. */
@Serializable
data class LocalShareDevice(val deviceId: String, val deviceName: String, val ready: Boolean)

@Serializable
data class LocalShareResult(val ok: Boolean, val message: String)

/** What the running app does for its Finder extensions over 127.0.0.1. Nothing it is given is written to disk. */
interface LocalExtensionApi {
    suspend fun devices(): List<LocalShareDevice>
    suspend fun send(paths: List<String>, deviceIds: List<String>): LocalShareResult
    suspend fun postBulletin(text: String, paths: List<String>): LocalShareResult
}
