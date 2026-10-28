package com.fileapex.notice

import com.fileapex.notice.NoticeSamples.notice
import com.fileapex.notice.NoticeSamples.parsed
import com.fileapex.notice.NoticeSamples.payload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeIndicatorsTest {
    private fun visible(severity: String, seen: Boolean = false, id: String = severity) =
        VisibleNotice(parsed(payload(1, NoticeSamples.ISSUED, notice(id = id, severity = severity))).notices.single(), seen)

    @Test
    fun infoOnlyAddsToTheCountNeverTheAlert() {
        val list = listOf(visible("info"), visible("info", id = "i2"), visible("info", seen = true, id = "i3"))
        assertFalse(NoticeIndicators.showsAlert(list))
        assertEquals(2, NoticeIndicators.plainCount(list))
    }

    @Test
    fun importantAlertsUntilSeenAndRequiredAlertsAlways() {
        assertTrue(NoticeIndicators.showsAlert(listOf(visible("important"))))
        assertFalse(NoticeIndicators.showsAlert(listOf(visible("important", seen = true))))
        assertTrue(NoticeIndicators.showsAlert(listOf(visible("required", seen = true))))
        assertFalse(NoticeIndicators.showsAlert(emptyList()))
    }

    @Test
    fun openingTheListingOnlyMarksInfoSeen() {
        val list = listOf(visible("info"), visible("important"), visible("required"))
        assertEquals(listOf("info"), NoticeIndicators.idsSeenByListing(list))
    }

    @Test
    fun requiredCannotBeDismissed() {
        assertFalse(NoticeIndicators.canDismiss(visible("required").notice))
        assertTrue(NoticeIndicators.canDismiss(visible("important").notice))
        assertTrue(NoticeIndicators.canDismiss(visible("info").notice))
    }

    @Test
    fun dismissalRulesBySeverity() {
        val day = NoticeIndicators.REAPPEAR_MS / 7
        assertTrue(NoticeIndicators.isHiddenByDismissal(visible("info").notice, 0L, 400 * day))
        assertTrue(NoticeIndicators.isHiddenByDismissal(visible("important").notice, 0L, 6 * day))
        assertFalse(NoticeIndicators.isHiddenByDismissal(visible("important").notice, 0L, 7 * day))
        assertFalse(NoticeIndicators.isHiddenByDismissal(visible("required").notice, 0L, day))
        assertFalse(NoticeIndicators.isHiddenByDismissal(visible("info").notice, null, day))
    }

    @Test
    fun theUpdateActionGoesToTheRightPlaceForEachFlavor() {
        assertEquals(NoticeUpdateTarget.InApp, NoticeIndicators.updateTarget(NoticeChannel.Github))
        assertEquals(
            NoticeUpdateTarget.Listing("https://play.google.com/store/apps/details?id=com.fileapex"),
            NoticeIndicators.updateTarget(NoticeChannel.Play)
        )
        assertEquals(
            NoticeUpdateTarget.Listing("https://f-droid.org/packages/com.fileapex/"),
            NoticeIndicators.updateTarget(NoticeChannel.Fdroid)
        )
    }

    @Test
    fun playOnlyEverLinksToItsOwnListing() {
        val extra = ""","link":"https://github.com/byhowiecreations/FileApex/releases","link_play":"https://play.google.com/store/apps/details?id=com.fileapex""""
        val n = parsed(payload(1, NoticeSamples.ISSUED, notice(extra = extra))).notices.single()
        assertEquals("https://play.google.com/store/apps/details?id=com.fileapex", NoticeIndicators.linkFor(n, NoticeChannel.Play))
        assertEquals("https://github.com/byhowiecreations/FileApex/releases", NoticeIndicators.linkFor(n, NoticeChannel.Github))
        val githubOnly = parsed(payload(1, NoticeSamples.ISSUED, notice(extra = ""","link":"https://github.com/byhowiecreations/FileApex""""))).notices.single()
        assertNull(NoticeIndicators.linkFor(githubOnly, NoticeChannel.Play))
    }
}
