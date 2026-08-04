package com.fileapex.cloud.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsRelayRequest(
    val action: String = "fetch"
)

@Serializable
data class DiagnosticsRelaySession(
    val sessionId: String,
    val requesterDeviceId: String,
    val responderDeviceId: String,
    val requestEncPayload: String = "",
    val responseEncPayload: String = "",
    val status: String = DiagnosticsRelayStatus.PENDING,
    val createdAtEpochMs: Long,
    val ttlEpochMs: Long
) {
    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= ttlEpochMs

    fun toFirestoreFields(): Map<String, Any> = mapOf(
        "sessionId" to sessionId,
        "requesterDeviceId" to requesterDeviceId,
        "responderDeviceId" to responderDeviceId,
        "requestEncPayload" to requestEncPayload,
        "responseEncPayload" to responseEncPayload,
        "status" to status,
        "createdAtEpochMs" to createdAtEpochMs,
        "ttlEpochMs" to ttlEpochMs
    )

    companion object {
        fun fromFirestore(data: Map<String, Any?>): DiagnosticsRelaySession? {
            val sessionId = data["sessionId"]?.toString()?.trim().orEmpty()
            if (sessionId.isBlank()) return null
            return DiagnosticsRelaySession(
                sessionId = sessionId,
                requesterDeviceId = data["requesterDeviceId"]?.toString().orEmpty(),
                responderDeviceId = data["responderDeviceId"]?.toString().orEmpty(),
                requestEncPayload = data["requestEncPayload"]?.toString().orEmpty(),
                responseEncPayload = data["responseEncPayload"]?.toString().orEmpty(),
                status = data["status"]?.toString()?.ifBlank { DiagnosticsRelayStatus.PENDING }
                    ?: DiagnosticsRelayStatus.PENDING,
                createdAtEpochMs = (data["createdAtEpochMs"] as? Number)?.toLong()
                    ?: data["createdAtEpochMs"]?.toString()?.toLongOrNull()
                    ?: 0L,
                ttlEpochMs = (data["ttlEpochMs"] as? Number)?.toLong()
                    ?: data["ttlEpochMs"]?.toString()?.toLongOrNull()
                    ?: 0L
            )
        }
    }
}
