package com.fileapex.notice

import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The two fields of `notice.json`. The signature covers the UTF-8 bytes of [payload] exactly as decoded from the JSON string. */
class NoticeEnvelope(val payload: String, val signature: ByteArray)

object NoticeEnvelopeParser {
    const val MAX_FILE_BYTES = 32 * 1024
    const val SIGNATURE_BYTES = 64

    @OptIn(ExperimentalEncodingApi::class)
    fun parse(file: ByteArray): NoticeEnvelope? {
        if (file.isEmpty() || file.size > MAX_FILE_BYTES) return null
        val root = NoticeJson.parseObject(file.decodeToString()) ?: return null
        val payload = (root["payload"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val sig = (root["sig"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val signature = try {
            Base64.decode(sig)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (signature.size != SIGNATURE_BYTES) return null
        return NoticeEnvelope(payload, signature)
    }
}
