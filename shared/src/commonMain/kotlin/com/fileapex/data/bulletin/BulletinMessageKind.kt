package com.fileapex.data.bulletin

import com.fileapex.data.note.NoteNotifyPolicy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object BulletinMessageKind {
    fun batteryAlertContent(levelPercent: Int?): String = when (levelPercent) {
        null -> "The battery is low."
        else -> "The battery level is $levelPercent%."
    }

    fun isBattery(contentType: Int, content: String): Boolean =
        contentType == BulletinContentType.BATTERY_LOW ||
            NoteNotifyPolicy.isCriticalBulletin(content)

    fun matchesRetract(
        messageContentType: Int,
        messageContent: String,
        retractContentType: Int
    ): Boolean {
        if (messageContentType == retractContentType) return true
        if (retractContentType == BulletinContentType.BATTERY_LOW) {
            return NoteNotifyPolicy.isCriticalBulletin(messageContent)
        }
        return false
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun retractPayloadId(originDeviceId: String, contentType: Int): String {
        val encoded = Base64.UrlSafe.encode("$originDeviceId|$contentType".encodeToByteArray())
        return "retract-kind-$encoded"
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decodeRetractPayloadId(payloadId: String): Pair<String, Int>? {
        if (!payloadId.startsWith("retract-kind-")) return null
        return runCatching {
            val decoded = Base64.UrlSafe.decode(payloadId.removePrefix("retract-kind-")).decodeToString()
            val splitAt = decoded.indexOf('|')
            if (splitAt <= 0) return null
            val originDeviceId = decoded.substring(0, splitAt)
            val contentType = decoded.substring(splitAt + 1).toInt()
            originDeviceId to contentType
        }.getOrNull()
    }
}
