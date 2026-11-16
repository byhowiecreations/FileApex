package com.fileapex.domain.link

import com.fileapex.domain.notifications.NotificationCompanionGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLinkWireTest {
    private val now = 5_000_000L

    // --- the query a health poll carries ---

    @Test
    fun aGuessKeepsTheLegacyQueryAndAChoiceAddsItsTime() {
        assertEquals("companion=1", HealthSignal(HealthSignal.Kind.Companion, PairLink.Signal(true, 0L)).query())
        assertEquals("companion=1&chosenAt=123", HealthSignal(HealthSignal.Kind.Companion, PairLink.Signal(true, 123L)).query())
        assertEquals("companion=0", HealthSignal(HealthSignal.Kind.Companion, PairLink.Signal(false, 0L)).query())
        assertEquals("companion=0&chosenAt=55", HealthSignal(HealthSignal.Kind.Companion, PairLink.Signal(false, 55L)).query())
        assertEquals("link=1&chosenAt=777", HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(true, 777L)).query())
        assertEquals("link=0", HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(false, 0L)).query())
    }

    // --- what the phone stores from a computer, and the computer from a phone ---

    @Test
    fun theGrantStoreKeepsALiveChoiceAgainstAnotherComputersGuess() {
        val first = NotificationCompanionGrant.recorded("", "amanda", PairLink.Signal(true, 900L), now)!!
        assertNull("a guess from another computer changes nothing", NotificationCompanionGrant.recorded(first, "mac", PairLink.Signal(true, 0L), now))
        val replaced = NotificationCompanionGrant.recorded(first, "mac", PairLink.Signal(true, 1_000L), now)!!
        assertEquals("mac", NotificationCompanionGrant.currentLink(replaced, now)!!.peerId)
    }

    @Test
    fun anOldGrantWithoutATimeStillReadsAndRenewsAsAGuess() {
        val legacy = """{"grantorDeviceId":"mac","active":true,"expiresAtEpochMs":${now + 1000}}"""
        val link = NotificationCompanionGrant.currentLink(legacy, now)!!
        assertEquals(LinkRecord("mac", true, 0L, now + 1000), link)
        val renewed = NotificationCompanionGrant.recorded(legacy, "mac", PairLink.Signal(true, 0L), now + 600_000L - 1)
        assertTrue("an unchanged grant is only rewritten when it is about to age", renewed != null)
    }

    @Test
    fun anUnchangedFreshGrantIsNotRewritten() {
        val stored = NotificationCompanionGrant.recorded("", "mac", PairLink.Signal(true, 0L), now)!!
        assertNull(NotificationCompanionGrant.recorded(stored, "mac", PairLink.Signal(true, 0L), now + 1_000L))
    }

    @Test
    fun theComputerStoresWhichPhoneAnnouncedItselfAndWhenItLeft() {
        val linked = PhoneLinkClaim.recorded("", "fold", PairLink.Signal(true, 900L), now)!!
        assertEquals(LinkRecord("fold", true, 900L, now + PairLink.LEASE_MS), PhoneLinkClaim.current(linked, now))
        assertNull("a pause is not a change of phone", PhoneLinkClaim.recorded(linked, "fold", PairLink.Signal(false, 0L), now + 10))
        val left = PhoneLinkClaim.recorded(linked, "fold", PairLink.Signal(false, 1_000L), now + 10)!!
        assertEquals(LinkRecord("fold", false, 1_000L, now + 10 + PairLink.LEASE_MS), PhoneLinkClaim.current(left, now + 10))
        assertEquals("a standing link is kept long after its lease", "fold", PhoneLinkClaim.current(linked, now + 10 * PairLink.LEASE_MS)!!.peerId)
        assertNull(PhoneLinkClaim.current("{not json", now))
        assertNull(PhoneLinkClaim.current("", now))
    }

    // --- peers that say they are linked to someone else ---

    @Test
    fun aPeerLinkedToAnotherDeviceIsSkippedByAGuessUntilItsNoteExpires() {
        val book = LinkedElsewhere()
        book.note("fold", linkedTo = "amanda", now = now)
        assertEquals(setOf("fold"), book.excludedFor(selfId = "mac", now = now + 1))
        assertEquals(emptySet<String>(), book.excludedFor(selfId = "mac", now = now + PairLink.LEASE_MS + 1))
    }

    @Test
    fun aPeerLinkedToThisDeviceOrToNobodyIsNotSkipped() {
        val book = LinkedElsewhere()
        book.note("fold", linkedTo = "mac", now = now)
        book.note("honor", linkedTo = null, now = now)
        assertEquals(emptySet<String>(), book.excludedFor(selfId = "mac", now = now + 1))
    }

    @Test
    fun aLaterNoteFromThePeerReplacesTheEarlierOne() {
        val book = LinkedElsewhere()
        book.note("fold", linkedTo = "amanda", now = now)
        book.note("fold", linkedTo = "mac", now = now + 10)
        assertEquals(emptySet<String>(), book.excludedFor(selfId = "mac", now = now + 11))
    }

    // --- what the phone announces when it changes partner ---

    @Test
    fun switchingToAnotherComputerTellsBothTheOldAndTheNewOne() {
        val calls = PhoneLink.announcements(previousId = "mac", nextId = "amanda", nextChosenAt = 900L)
        assertEquals(
            listOf(
                "mac" to HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(false, 900L)),
                "amanda" to HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(true, 900L))
            ),
            calls
        )
    }

    @Test
    fun followingAComputerTellsOnlyTheOneLeftBecauseThatComputerAlreadyKnows() {
        assertEquals(
            listOf("amanda" to HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(false, 0L))),
            PhoneLink.announcements(previousId = "amanda", nextId = "mac", nextChosenAt = 0L)
        )
    }

    @Test
    fun nothingChangedOrNothingShownTellsNobody() {
        assertEquals(emptyList<Pair<String, HealthSignal>>(), PhoneLink.announcements("mac", "mac", 900L))
        assertEquals(emptyList<Pair<String, HealthSignal>>(), PhoneLink.announcements("", "", 0L))
    }

    @Test
    fun leavingSimpleHomeStandsDownTheOldPartner() {
        assertEquals(
            listOf("mac" to HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(false, 0L))),
            PhoneLink.announcements(previousId = "mac", nextId = "", nextChosenAt = 0L)
        )
    }

    @Test
    fun theSweepRenewsOnlyAChosenLinkToTheShownComputer() {
        assertEquals(HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(true, 900L)), PhoneLink.sweepSignal("amanda", shownId = "amanda", chosenAt = 900L))
        assertNull(PhoneLink.sweepSignal("mac", shownId = "amanda", chosenAt = 900L))
        assertNull("a followed computer already knows", PhoneLink.sweepSignal("mac", shownId = "mac", chosenAt = 0L))
    }
}
