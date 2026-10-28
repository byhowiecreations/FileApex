package com.fileapex.notice

sealed interface NoticeReadResult {
    data class Accepted(val payload: NoticePayload) : NoticeReadResult
    data object Oversize : NoticeReadResult
    data object Malformed : NoticeReadResult
    data object BadSignature : NoticeReadResult
    data object UnknownVersion : NoticeReadResult
    data object InvalidPayload : NoticeReadResult
    data object StaleSerial : NoticeReadResult
}

object NoticeFileReader {
    /** Verifies before parsing the payload; a file is accepted only with a valid signature and a serial not below [highestAcceptedSerial]. */
    fun read(file: ByteArray, publicKeys: List<ByteArray>, highestAcceptedSerial: Long): NoticeReadResult {
        if (file.size > NoticeEnvelopeParser.MAX_FILE_BYTES) return NoticeReadResult.Oversize
        val envelope = NoticeEnvelopeParser.parse(file) ?: return NoticeReadResult.Malformed
        if (!noticeVerifySignature(envelope.payload.encodeToByteArray(), envelope.signature, publicKeys)) {
            return NoticeReadResult.BadSignature
        }
        return when (val parsed = NoticePayloadParser.parse(envelope.payload)) {
            NoticePayloadResult.UnknownVersion -> NoticeReadResult.UnknownVersion
            NoticePayloadResult.Invalid -> NoticeReadResult.InvalidPayload
            is NoticePayloadResult.Ok ->
                if (parsed.payload.serial < highestAcceptedSerial) NoticeReadResult.StaleSerial
                else NoticeReadResult.Accepted(parsed.payload)
        }
    }
}
