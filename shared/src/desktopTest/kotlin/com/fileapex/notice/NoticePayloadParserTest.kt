package com.fileapex.notice

import com.fileapex.notice.NoticeSamples.notice
import com.fileapex.notice.NoticeSamples.payload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticePayloadParserTest {
    private fun parse(payload: String) = NoticePayloadParser.parse(payload)

    private fun notices(payload: String) = (parse(payload) as NoticePayloadResult.Ok).payload.notices

    @Test
    fun aFullNoticeParses() {
        val extra = ""","from":"0.19.9","below":"0.20.0","platforms":["android","macos","docker"],"channels":["github","play"],""" +
            """"requires":["google_link","iroh"],"link":"https://github.com/byhowiecreations/FileApex/releases"""" +
            ""","link_play":"https://play.google.com/store/apps/details?id=com.fileapex""""
        val n = notices(payload(7, ISSUED, notice(severity = "required", extra = extra))).single()
        assertEquals(NoticeSeverity.Required, n.severity)
        assertEquals(setOf(NoticePlatform.Android, NoticePlatform.Macos, NoticePlatform.Docker), n.platforms)
        assertEquals(setOf(NoticeFeature.GoogleLink, NoticeFeature.Iroh), n.requires)
        assertEquals("https://github.com/byhowiecreations/FileApex/releases", n.link)
        assertEquals("https://play.google.com/store/apps/details?id=com.fileapex", n.linkPlay)
        assertEquals(7L, (parse(payload(7, ISSUED, notice())) as NoticePayloadResult.Ok).payload.serial)
    }

    @Test
    fun theUpdateActionIsKnownAndAnythingElseIsIgnored() {
        assertEquals(NoticeAction.Update, notices(payload(1, ISSUED, notice(extra = ""","action":"update""""))).single().action)
        assertNull(notices(payload(1, ISSUED, notice())).single().action)
        assertNull(notices(payload(1, ISSUED, notice(extra = ""","action":"format-disk""""))).single().action)
    }

    @Test
    fun anUnknownFormatVersionIsNotAnError() {
        assertEquals(NoticePayloadResult.UnknownVersion, parse(payload(1, ISSUED, notice(), v = 2)))
    }

    @Test
    fun structuralDamageRejectsTheWholePayload() {
        assertEquals(NoticePayloadResult.Invalid, parse("not json"))
        assertEquals(NoticePayloadResult.Invalid, parse("[]"))
        assertEquals(NoticePayloadResult.Invalid, parse("""{"v":1,"serial":-1,"issued":"$ISSUED","notices":[]}"""))
        assertEquals(NoticePayloadResult.Invalid, parse("""{"v":1,"serial":1,"issued":"2026-02-30","notices":[]}"""))
        assertEquals(NoticePayloadResult.Invalid, parse("""{"v":1,"serial":1,"issued":"$ISSUED"}"""))
        assertEquals(NoticePayloadResult.Invalid, parse("""{"v":"1","serial":1,"issued":"$ISSUED","notices":[]}"""))
    }

    @Test
    fun deeplyNestedJsonDoesNotCrash() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        assertEquals(NoticePayloadResult.Invalid, parse("""{"v":1,"serial":1,"issued":"$ISSUED","notices":$deep}"""))
    }

    @Test
    fun aBrokenNoticeIsDroppedAndTheRestKept() {
        val bad = listOf(
            notice(id = "bad id"),
            notice(severity = "loud"),
            notice(extra = ""","from":"banana""""),
            notice(extra = ""","to":"0.19.9","below":"0.20.0""""),
            """{"id":"noexpiry","severity":"info","title":{"en":"t"},"text":{"en":"x"}}""",
            """{"id":"noen","severity":"info","expires":"2027-03-01","title":{"es":"t"},"text":{"es":"x"}}"""
        )
        val all = notices(payload(1, ISSUED, *bad.toTypedArray(), notice(id = "good")))
        assertEquals(listOf("good"), all.map { it.id })
    }

    @Test
    fun duplicateIdsKeepTheFirst() {
        assertEquals(1, notices(payload(1, ISSUED, notice(id = "a"), notice(id = "a"))).size)
    }

    @Test
    fun expiryMoreThanTwelveMonthsAfterIssuedIsIgnored() {
        assertEquals(1, notices(payload(1, ISSUED, notice(expires = "2027-10-29"))).size)
        assertTrue(notices(payload(1, ISSUED, notice(expires = "2027-11-05"))).isEmpty())
    }

    @Test
    fun tooManyNoticesRejectTheFile() {
        val many = Array(NoticePayloadParser.MAX_NOTICES + 1) { notice(id = "n$it") }
        assertEquals(NoticePayloadResult.Invalid, parse(payload(1, ISSUED, *many)))
    }

    @Test
    fun textIsPlainAndCut() {
        val shown = notices(
            payload(
                1, ISSUED,
                """{"id":"t","severity":"info","expires":"2027-03-01",
                    "title":{"en":"<b>Hi</b> **there**‮ evil\u0000​"},
                    "text":{"en":"${"x".repeat(900)}","es":"hola"}}""".replace("\n", "")
            )
        ).single()
        assertEquals("<b>Hi</b> **there** evil", shown.title.getValue("en"))
        assertEquals(NoticeText.TEXT_MAX, shown.text.getValue("en").length)
        assertEquals("hola", shown.textFor("es"))
        assertEquals(shown.text.getValue("en"), shown.textFor("zh"))
    }

    @Test
    fun overLongTitleIsCutWithoutSplittingASurrogatePair() {
        val title = "a".repeat(NoticeText.TITLE_MAX - 1) + "😀"
        val n = notices(
            payload(1, ISSUED, """{"id":"t","severity":"info","expires":"2027-03-01","title":{"en":"$title"},"text":{"en":"x"}}""")
        ).single()
        assertEquals(NoticeText.TITLE_MAX - 1, n.title.getValue("en").length)
    }

    @Test
    fun linksOffTheAllowlistAreDropped() {
        fun link(url: String) = notices(payload(1, ISSUED, notice(extra = ""","link":"$url""""))).single().link
        assertEquals("https://github.com/byhowiecreations/FileApex", link("https://github.com/byhowiecreations/FileApex"))
        assertNull(link("https://github.com/byhowiecreations/FileApexEvil"))
        assertNull(link("https://github.com/byhowiecreations/FileApex.evil.com/x"))
        assertNull(link("http://github.com/byhowiecreations/FileApex"))
        assertNull(link("https://evil.example/https://github.com/byhowiecreations/FileApex"))
        assertNull(link("https://fileapex.pages.dev/"))
        assertNull(link("https://github.com/byhowiecreations/FileApex/a b"))
    }

    @Test
    fun unknownTargetNamesAreSkippedAndNeverMatchEveryone() {
        val n = notices(payload(1, ISSUED, notice(extra = ""","platforms":["plan9"],"requires":["telepathy"]"""))).single()
        assertTrue(n.platforms!!.isEmpty())
        assertFalse(NoticeSelector.matches(n, NoticeSamples.context()))
    }

    private companion object {
        const val ISSUED = NoticeSamples.ISSUED
    }
}
