package com.fileapex.drop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDropLinkTest {
    private val link = GuestDropLink(
        endpointId = "ab".repeat(32),
        relayUrl = "https://relay.getfileapex.com/",
        dropId = "cd".repeat(16),
        secret = "ef".repeat(16),
        expiresAtMs = 1_800_000_000_000L,
        name = "Holiday photos é #1.zip",
        sizeBytes = 123_456_789_012L
    )

    @Test fun theLinkPointsAtTheDropPageAndKeepsEverythingAfterTheHash() {
        val url = link.toUrl()
        assertTrue(url.startsWith("https://getfileapex.com/drop/#v1."))
        val beforeHash = url.substringBefore('#')
        assertFalse(beforeHash.contains(link.secret))
        assertFalse(beforeHash.contains(link.endpointId))
        assertFalse(beforeHash.contains("Holiday"))
    }

    @Test fun aLinkReadsBackExactly() {
        assertEquals(link, GuestDropLink.parse(link.toUrl()))
    }

    @Test fun aLinkInsideAMessageIsFound() {
        assertEquals(link, GuestDropLink.parse("here you go ${link.toUrl()} thanks"))
    }

    @Test fun nothingButAnHttpsDropLinkIsAccepted() {
        val fragment = link.toUrl().substringAfter('#')
        assertNull(GuestDropLink.parse("http://getfileapex.com/drop/#$fragment"))
        assertNull(GuestDropLink.parse("https://evil.example/drop/#$fragment"))
        assertNull(GuestDropLink.parse("https://getfileapex.com/other/#$fragment"))
        assertNull(GuestDropLink.parse("https://getfileapex.com/drop/"))
        assertNull(GuestDropLink.parse("https://getfileapex.com/drop/#v2.${fragment.removePrefix("v1.")}"))
        assertNull(GuestDropLink.parse("https://getfileapex.com/drop/#v1.!!notbase64!!"))
    }

    @Test fun aPayloadWithTheWrongFieldsIsRefused() {
        fun encode(vararg fields: String) = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(fields.joinToString("\n").encodeToByteArray())
        val ok = listOf(link.endpointId, link.relayUrl, link.dropId, link.secret, link.expiresAtMs.toString(), link.sizeBytes.toString(), link.name)
        fun url(fields: List<String>) = "https://getfileapex.com/drop/#v1.${encode(*fields.toTypedArray())}"
        assertEquals(link, GuestDropLink.parse(url(ok)))
        assertNull(GuestDropLink.parse(url(ok.drop(1))))
        assertNull(GuestDropLink.parse(url(ok.toMutableList().also { it[0] = "zz" })))
        assertNull(GuestDropLink.parse(url(ok.toMutableList().also { it[2] = "short" })))
        assertNull(GuestDropLink.parse(url(ok.toMutableList().also { it[3] = "short" })))
        assertNull(GuestDropLink.parse(url(ok.toMutableList().also { it[4] = "soon" })))
        assertNull(GuestDropLink.parse(url(ok.toMutableList().also { it[5] = "-1" })))
        assertNull(GuestDropLink.parse(url(ok.toMutableList().also { it[1] = "ftp://relay" })))
    }

    @Test fun theLinkNeverCarriesAnIpAddressOrAPath() {
        val text = java.util.Base64.getUrlDecoder().decode(link.toUrl().substringAfter("#v1.")).decodeToString()
        assertFalse(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").containsMatchIn(text))
        assertFalse(text.contains("/Users") || text.contains("/storage"))
    }

    private val keyed = link.copy(guestKeys = 2)

    @Test fun aLinkWithGuestKeysUsesTheSecondFormatAndReadsBackExactly() {
        val url = keyed.toUrl()
        assertTrue(url.startsWith("https://getfileapex.com/drop/#v2."))
        assertEquals(keyed, GuestDropLink.parse(url))
        assertEquals(0, GuestDropLink.parse(link.toUrl())!!.guestKeys)
        assertTrue(link.toUrl().startsWith("https://getfileapex.com/drop/#v1."))
    }

    @Test fun theSecondFormatNeedsAGuestKeyCountInRange() {
        fun encode(vararg fields: String) = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(fields.joinToString("\n").encodeToByteArray())
        fun v2(count: String) = "https://getfileapex.com/drop/#v2." + encode(
            keyed.endpointId, keyed.relayUrl, keyed.dropId, keyed.secret, keyed.expiresAtMs.toString(), keyed.sizeBytes.toString(), count, keyed.name
        )
        assertEquals(keyed, GuestDropLink.parse(v2("2")))
        for (bad in listOf("0", "9", "-1", "two", "")) assertNull(bad, GuestDropLink.parse(v2(bad)))
    }
}

