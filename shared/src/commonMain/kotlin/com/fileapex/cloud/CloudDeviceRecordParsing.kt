package com.fileapex.cloud

/** SSOT for parsing Firestore device document fields. */
object CloudDeviceRecordParsing {
    fun fromFirestoreMap(data: Map<String, Any?>, documentId: String): CloudDeviceRecord {
        val id = data["deviceId"]?.toString()?.trim().orEmpty().ifBlank { documentId }
        return CloudDeviceRecord(
            deviceId = id,
            deviceName = data["deviceName"]?.toString().orEmpty(),
            lastKnownIp = data["lastKnownIp"]?.toString().orEmpty(),
            port = (data["port"] as? Number)?.toInt()
                ?: data["port"]?.toString()?.toIntOrNull()
                ?: 8080,
            publicKeyHash = data["publicKeyHash"]?.toString().orEmpty(),
            rootPath = data["rootPath"]?.toString().orEmpty(),
            platform = data["platform"]?.toString().orEmpty(),
            clientVersion = data["clientVersion"]?.toString().orEmpty(),
            clientVersionCode = (data["clientVersionCode"] as? Number)?.toInt()
                ?: data["clientVersionCode"]?.toString()?.toIntOrNull()
                ?: 0,
            updatedAtEpochMs = (data["updatedAtEpochMs"] as? Number)?.toLong()
                ?: data["updatedAtEpochMs"]?.toString()?.toLongOrNull()
                ?: 0L,
            fcmToken = data["fcmToken"]?.toString().orEmpty(),
            diagnosticsPublicKey = data["diagnosticsPublicKey"]?.toString().orEmpty(),
            deviceDetailsCloudEnabled = data["deviceDetailsCloudEnabled"] as? Boolean == true,
            hardwareFingerprint = (data["hardwareFingerprint"] as? Map<*, *>)
                ?.mapNotNull { (k, v) ->
                    val key = k?.toString()?.trim() ?: return@mapNotNull null
                    val valStr = v?.toString()?.trim() ?: return@mapNotNull null
                    key to valStr
                }?.toMap().orEmpty()
        )
    }
}
