package com.fileapex.domain.devices

import java.util.Base64
import kotlinx.serialization.Serializable

/** "Show this picture for [targetDeviceId]" from a paired device; an empty [png] clears the icon. The picture is a PNG in base64. */
@Serializable
data class DeviceIconSyncRequest(
    val senderDeviceId: String,
    val senderPublicKey: String,
    val targetDeviceId: String,
    val png: String = ""
)

/** What this device accepts from a peer that wants to set an icon. Everything in the request is treated as hostile input. */
object DeviceIconSyncPolicy {
    const val MAX_PNG_BYTES = 150 * 1024
    const val MAX_BODY_BYTES = 256L * 1024
    const val MIN_INTERVAL_MS = 300L
    private const val MIN_EDGE = 16
    private const val MAX_EDGE = 512
    private val targetPattern = Regex("""^[A-Za-z0-9._:-]{1,128}$""")

    sealed interface Verdict {
        class Set(val png: ByteArray) : Verdict
        data object Clear : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    /** Checks everything that can be checked without decoding the picture; [targetKnown] is whether the target is a paired device here. */
    fun check(request: DeviceIconSyncRequest, targetKnown: Boolean, lastAcceptedFromSenderMs: Long?, nowMs: Long): Verdict {
        if (!targetPattern.matches(request.targetDeviceId)) return Verdict.Rejected("bad_target")
        if (!targetKnown) return Verdict.Rejected("unknown_target")
        if (lastAcceptedFromSenderMs != null && nowMs - lastAcceptedFromSenderMs < MIN_INTERVAL_MS) return Verdict.Rejected("rate_limited")
        if (request.png.isEmpty()) return Verdict.Clear
        if (request.png.length > MAX_PNG_BYTES * 4 / 3 + 8) return Verdict.Rejected("too_large")
        val bytes = runCatching { Base64.getDecoder().decode(request.png) }.getOrNull() ?: return Verdict.Rejected("bad_image")
        if (bytes.size > MAX_PNG_BYTES) return Verdict.Rejected("too_large")
        return Verdict.Set(bytes)
    }

    fun dimensionsOk(width: Int, height: Int): Boolean = width in MIN_EDGE..MAX_EDGE && height in MIN_EDGE..MAX_EDGE
}
