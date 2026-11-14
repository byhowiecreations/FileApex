package com.fileapex.domain.clipboard

import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.platform.PlatformClipboard
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Bounded E2EE payload framing for syncing small clipboard images (e.g. screenshots) over the
 * existing encrypted clipboard channel without breaking backward text routing.
 */
object ClipboardImageEnvelope {
    const val PREFIX = "FILEAPEX_CLIP_IMG_V1:"
    const val MAX_RAW_IMAGE_BYTES = 300_000

    data class DecodedImage(val mimeType: String, val bytes: ByteArray)

    fun isEnvelope(raw: String): Boolean = raw.startsWith(PREFIX)

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(imageBytes: ByteArray, mimeType: String = "image/png"): String? {
        if (imageBytes.isEmpty() || imageBytes.size > MAX_RAW_IMAGE_BYTES) return null
        val safeMime = mimeType.trim().lowercase().takeIf { it.startsWith("image/") } ?: "image/png"
        val body = Base64.encode(imageBytes)
        return "$PREFIX$safeMime\n$body"
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decode(raw: String): DecodedImage? {
        if (!isEnvelope(raw)) return null
        val rest = raw.removePrefix(PREFIX)
        val newline = rest.indexOf('\n')
        if (newline <= 0) return null
        val mime = rest.substring(0, newline).trim().lowercase()
        if (!mime.startsWith("image/")) return null
        val b64 = rest.substring(newline + 1).trim()
        if (b64.isEmpty()) return null
        val decoded = runCatching { Base64.decode(b64) }.getOrNull() ?: return null
        if (decoded.isEmpty() || decoded.size > MAX_RAW_IMAGE_BYTES) return null
        return DecodedImage(mimeType = mime, bytes = decoded)
    }

    fun dedupeKey(imageBytes: ByteArray): String {
        // Stable FNV-1a 64-bit + length key sufficient for duplicate suppression in ClipboardPushDeduper.
        var hash = -3750763034362895579L
        for (b in imageBytes) {
            hash = hash xor (b.toLong() and 0xffL)
            hash *= 1099511628211L
        }
        return "img:${imageBytes.size}:$hash"
    }

    fun dedupeKeyForPayload(payload: String): String =
        decode(payload)?.let { dedupeKey(it.bytes) } ?: payload

    fun encodeImageOrNull(imageBytes: ByteArray?): String? =
        if (imageBytes == null) null else encode(imageBytes, "image/jpeg")

    fun encodeIfEnabled(imageBytes: ByteArray?, enabled: Boolean): String? =
        if (!enabled) null else encodeImageOrNull(imageBytes)

    suspend fun applyDecodedIfImage(
        plaintext: String,
        senderDeviceName: String,
        onApplied: suspend (DecodedImage, String) -> Unit
    ): Boolean {
        val decoded = decode(plaintext) ?: return false
        ClipboardPushDeduper.remember(dedupeKey(decoded.bytes))
        onApplied(decoded, senderDeviceName)
        return true
    }

    fun currentClipboardPayloadOrNull(): String? {
        return preparedManualText(PlatformClipboard.getSystemClipboardText())
            ?: encodeImageOrNull(PlatformClipboard.getSystemClipboardImageBytes())
    }

    fun preparedManualText(raw: String?): String? =
        (ClipboardCopySignals.prepare(raw) as? ClipboardCopySignals.Prepared.Ok)?.text

    fun preparedManualError(raw: String?): String = when (ClipboardCopySignals.prepare(raw)) {
        is ClipboardCopySignals.Prepared.Ok, ClipboardCopySignals.Prepared.Empty -> AppI18n.t("clipboard_empty")
        ClipboardCopySignals.Prepared.TooLarge -> AppI18n.t("clipboard_too_large")
        ClipboardCopySignals.Prepared.NotText -> AppI18n.t("clipboard_not_text")
    }

    fun preparedAutomaticText(raw: String?): String? = when (val prepared = ClipboardCopySignals.prepare(raw)) {
        is ClipboardCopySignals.Prepared.Ok -> prepared.text
        ClipboardCopySignals.Prepared.Empty -> null
        ClipboardCopySignals.Prepared.TooLarge -> { BriefToast.show(AppI18n.t("clipboard_too_large")); null }
        ClipboardCopySignals.Prepared.NotText -> { BriefToast.show(AppI18n.t("clipboard_not_text")); null }
    }
}
