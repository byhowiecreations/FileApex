package com.omninode.domain.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PairingPayload(
    val v: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val rootPath: String,
    val publicKeyHash: String = "",
    /** When true, the scanner must supply this device's PIN to complete pairing. */
    val pinRequired: Boolean = false
) {
    fun toQrText(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun parse(qrText: String): PairingPayload {
            return json.decodeFromString(serializer(), qrText.trim())
        }
    }
}

object PairingPayloadFactory {
    fun create(
        deviceId: String,
        deviceName: String,
        host: String,
        port: Int,
        rootPath: String,
        publicKeyHash: String = "",
        pinRequired: Boolean = false
    ): PairingPayload {
        return PairingPayload(
            deviceId = deviceId,
            deviceName = deviceName,
            host = host,
            port = port,
            rootPath = rootPath,
            publicKeyHash = publicKeyHash,
            pinRequired = pinRequired
        )
    }
}
