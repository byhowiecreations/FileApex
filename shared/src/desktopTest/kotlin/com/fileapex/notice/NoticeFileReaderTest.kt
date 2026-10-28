package com.fileapex.notice

import com.fileapex.notice.NoticeSamples.notice
import com.fileapex.notice.NoticeSamples.payload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeFileReaderTest {
    private val key = NoticeTestKey()
    private val keys = listOf(key.publicKey)
    private val good = payload(5, NoticeSamples.ISSUED, notice(id = "a"))

    private fun read(file: ByteArray, highest: Long = 0, trusted: List<ByteArray> = keys) =
        NoticeFileReader.read(file, trusted, highest)

    @Test
    fun aValidFileIsAccepted() {
        val result = read(key.file(good)) as NoticeReadResult.Accepted
        assertEquals(5L, result.payload.serial)
        assertEquals(listOf("a"), result.payload.notices.map { it.id })
    }

    @Test
    fun oneChangedByteInThePayloadBreaksTheSignature() {
        val file = envelope(good.replace("\"a\"", "\"b\""), key.sign(good))
        assertEquals(NoticeReadResult.BadSignature, read(file))
    }

    @Test
    fun aSignatureFromAnotherKeyIsRefused() {
        assertEquals(NoticeReadResult.BadSignature, read(NoticeTestKey().file(good)))
    }

    @Test
    fun withNoTrustedKeyEverythingIsRefused() {
        assertEquals(NoticeReadResult.BadSignature, read(key.file(good), trusted = emptyList()))
    }

    @Test
    fun eitherOfTwoKeysVerifies() {
        val backup = NoticeTestKey()
        val both = listOf(key.publicKey, backup.publicKey)
        assertTrue(read(backup.file(good), trusted = both) is NoticeReadResult.Accepted)
        assertTrue(read(key.file(good), trusted = both) is NoticeReadResult.Accepted)
    }

    @Test
    fun missingOrDamagedSignatureFieldsAreMalformed() {
        assertEquals(NoticeReadResult.Malformed, read("""{"payload":"x"}""".encodeToByteArray()))
        assertEquals(NoticeReadResult.Malformed, read("""{"payload":"x","sig":"@@@"}""".encodeToByteArray()))
        assertEquals(NoticeReadResult.Malformed, read("""{"payload":"x","sig":"AAAA"}""".encodeToByteArray()))
        assertEquals(NoticeReadResult.Malformed, read("not json".encodeToByteArray()))
        assertEquals(NoticeReadResult.Malformed, read(ByteArray(0)))
    }

    @Test
    fun anOversizeFileIsRefusedBeforeAnythingElse() {
        val big = key.file(payload(1, NoticeSamples.ISSUED, notice()) + " ".repeat(NoticeEnvelopeParser.MAX_FILE_BYTES))
        assertEquals(NoticeReadResult.Oversize, read(big))
    }

    @Test
    fun anUnknownFormatVersionIsReportedNotShown() {
        assertEquals(NoticeReadResult.UnknownVersion, read(key.file(payload(1, NoticeSamples.ISSUED, notice(), v = 2))))
    }

    @Test
    fun aSignedButStructurallyBrokenPayloadIsInvalid() {
        assertEquals(NoticeReadResult.InvalidPayload, read(key.file("""{"v":1,"serial":1}""")))
    }

    @Test
    fun aLowerSerialIsReplayedAndRefusedButEqualAndHigherPass() {
        val file = key.file(good)
        assertEquals(NoticeReadResult.StaleSerial, read(file, highest = 6))
        assertTrue(read(file, highest = 5) is NoticeReadResult.Accepted)
        assertTrue(read(file, highest = 4) is NoticeReadResult.Accepted)
    }

    @Test
    fun theSignatureCoversTheDecodedStringSoWhitespaceAroundTheEnvelopeIsHarmless() {
        val spaced = "\n  ".encodeToByteArray() + key.file(good) + "\n".encodeToByteArray()
        assertTrue(read(spaced) is NoticeReadResult.Accepted)
    }

    @Test
    fun nonAsciiPayloadSignsAndVerifiesOverUtf8() {
        val text = payload(2, NoticeSamples.ISSUED, notice(extra = "").replace("Body", "Actualiza ahora ñ 更新"))
        assertTrue(read(key.file(text)) is NoticeReadResult.Accepted)
    }

    @Test
    fun aHostileDeeplyNestedFileIsMalformedAndDoesNotCrash() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        assertEquals(NoticeReadResult.Malformed, read("""{"payload":"x","sig":$deep}""".encodeToByteArray()))
        assertEquals(NoticeReadResult.InvalidPayload, read(key.file("""{"v":1,"serial":1,"issued":"2026-10-28","notices":$deep}""")))
    }
}
