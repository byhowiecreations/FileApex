package com.fileapex.domain.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLinkTest {
    private val now = 1_000_000L
    private val lease = PairLink.LEASE_MS

    private fun claim(peer: String, active: Boolean = true, at: Long = 0L, expires: Long = now + lease) =
        LinkRecord(peer, active, at, expires)

    // --- wire ---

    @Test
    fun aGuessAndAChoiceAreEncodedWithTheLegacyActiveFlagFirst() {
        assertEquals("1", PairLink.activeValue(PairLink.Signal(true, 0L)))
        assertEquals("0", PairLink.activeValue(PairLink.Signal(false, 0L)))
        assertEquals(0L, PairLink.chosenAtValue(PairLink.Signal(true, 0L)))
        assertEquals(1_700_000_000_000L, PairLink.chosenAtValue(PairLink.Signal(true, 1_700_000_000_000L)))
    }

    @Test
    fun aSignalIsParsedFromItsQueryValuesAndAnOldBuildSendsNoTime() {
        assertEquals(PairLink.Signal(true, 0L), PairLink.parse("1", null))
        assertEquals(PairLink.Signal(true, 55L), PairLink.parse("1", "55"))
        assertEquals("a stand-down keeps the time the user switched", PairLink.Signal(false, 55L), PairLink.parse("0", "55"))
        assertEquals("a pause carries no time", PairLink.Signal(false, 0L), PairLink.parse("0", null))
        assertNull(PairLink.parse("", null))
        assertNull(PairLink.parse("maybe", null))
        assertEquals(PairLink.Signal(true, 0L), PairLink.parse("1", "not a number"))
    }

    // --- merge: what a device keeps when a partner announces ---

    @Test
    fun anAnnouncementFromTheCurrentPartnerRenewsTheRecord() {
        val merged = PairLink.merge(claim("mac", at = 10, expires = now + 1000), "mac", PairLink.Signal(true, 10), now)
        assertEquals(LinkRecord("mac", true, 10, now + lease), merged)
    }

    @Test
    fun aPlainRenewalFromThePartnerKeepsWhenItsChoiceWasMade() {
        val merged = PairLink.merge(claim("mac", at = 777), "mac", PairLink.Signal(true, 0L), now)!!
        assertEquals(777L, merged.chosenAtEpochMs)
    }

    @Test
    fun aGuessNeverReplacesALiveChoiceFromAnotherPeer() {
        val existing = claim("amanda", at = 500)
        assertEquals(existing, PairLink.merge(existing, "mac", PairLink.Signal(true, 0L), now))
    }

    @Test
    fun onlyANewerChoiceReplacesAStandingLinkAndAGuessNeverDoes() {
        assertEquals("mac", PairLink.merge(claim("amanda", at = 500), "mac", PairLink.Signal(true, 900), now)!!.peerId)
        assertEquals("amanda", PairLink.merge(claim("amanda", at = 900), "mac", PairLink.Signal(true, 500), now)!!.peerId)
        assertEquals("amanda", PairLink.merge(claim("amanda", at = 0), "mac", PairLink.Signal(true, 0L), now)!!.peerId)
        assertEquals("mac", PairLink.merge(claim("amanda", at = 0), "mac", PairLink.Signal(true, 30), now)!!.peerId)
    }

    @Test
    fun aReleasedRecordThatExpiredIsReplacedByAnyAnnouncement() {
        val stale = claim("amanda", active = false, at = 900, expires = now - 1)
        assertEquals("mac", PairLink.merge(stale, "mac", PairLink.Signal(true, 0L), now)!!.peerId)
    }

    @Test
    fun aStandingLinkNeverLapsAndOnlyANewerChoiceReplacesIt() {
        val standing = claim("amanda", at = 900, expires = now - 1)
        assertEquals(standing.peerId, PairLink.merge(standing, "mac", PairLink.Signal(true, 0L), now)!!.peerId)
        assertEquals("mac", PairLink.merge(standing, "mac", PairLink.Signal(true, 1_000L), now)!!.peerId)
    }

    @Test
    fun aPauseFromThePartnerChangesNothingButAReleaseEndsTheLinkAtTheTimeTheUserSwitched() {
        val existing = claim("mac", at = 10)
        assertEquals("leaving the screen is not a change of partner", existing, PairLink.merge(existing, "mac", PairLink.Signal(false, 0L), now))
        assertEquals(LinkRecord("mac", false, 900, now + lease), PairLink.merge(existing, "mac", PairLink.Signal(false, 900), now))
        assertEquals("a release older than the choice is stale", existing, PairLink.merge(existing, "mac", PairLink.Signal(false, 5), now))
    }

    @Test
    fun aStandDownFromSomeoneElseDoesNotClearALivePartner() {
        val existing = claim("amanda", at = 500)
        assertEquals(existing, PairLink.merge(existing, "mac", PairLink.Signal(false, 0L), now))
        assertEquals(existing, PairLink.merge(existing, "mac", PairLink.Signal(false, 900), now))
    }

    @Test
    fun aReleaseWithNoRecordIsRememberedAndAPauseIsNot() {
        assertEquals(LinkRecord("mac", false, 900, now + lease), PairLink.merge(null, "mac", PairLink.Signal(false, 900), now))
        assertNull(PairLink.merge(null, "mac", PairLink.Signal(false, 0L), now))
    }

    // --- decide: which peer a device shows ---

    @Test
    fun withNoChoiceTheLivePartnerIsFollowedWhateverKindOfAnnouncementItWas() {
        assertEquals(PairLink.Decision("", "mac", emptySet()), PairLink.decide("", 0L, claim("mac"), now))
        assertEquals(PairLink.Decision("", "mac", emptySet()), PairLink.decide("", 0L, claim("mac", at = 800), now))
    }

    @Test
    fun withNoRecordTheOwnChoiceOrNothingIsUsed() {
        assertEquals(PairLink.Decision("amanda", "", emptySet()), PairLink.decide("amanda", 100L, null, now))
        assertEquals(PairLink.Decision("", "", emptySet()), PairLink.decide("", 0L, null, now))
    }

    @Test
    fun aGuessNeverBeatsAChoice() {
        assertEquals(PairLink.Decision("amanda", "", emptySet()), PairLink.decide("amanda", 100L, claim("mac", at = 0), now))
    }

    @Test
    fun theLaterOfTwoChoicesWinsOnBothEnds() {
        // The phone chose Amanda at 900; the Mac chose the phone at 500. The phone keeps Amanda...
        assertEquals(PairLink.Decision("amanda", "", emptySet()), PairLink.decide("amanda", 900L, claim("mac", at = 500), now))
        // ...and a phone that chose Amanda at 300 yields to the Mac's later choice.
        assertEquals(PairLink.Decision("", "mac", emptySet()), PairLink.decide("amanda", 300L, claim("mac", at = 500), now))
    }

    @Test
    fun aChoiceOfTheSamePeerAsTheRecordNeedsNoFollowing() {
        assertEquals(PairLink.Decision("mac", "", emptySet()), PairLink.decide("mac", 300L, claim("mac", at = 900), now))
    }

    @Test
    fun aStandingLinkNeverLapsButAReleasedOneDoes() {
        assertEquals(PairLink.Decision("", "mac", emptySet()), PairLink.decide("amanda", 100L, claim("mac", at = 900, expires = now - 1), now))
        assertEquals(PairLink.Decision("", "mac", emptySet()), PairLink.decide("", 0L, claim("mac", expires = now - 1), now))
        assertEquals(PairLink.Decision("amanda", "", emptySet()), PairLink.decide("amanda", 100L, claim("mac", active = false, at = 900, expires = now - 1), now))
        assertEquals(PairLink.Decision("", "", emptySet()), PairLink.decide("", 0L, claim("mac", active = false, expires = now - 1), now))
    }

    @Test
    fun aPartnerThatLeftAfterMyChoiceIsGivenUpAndNotGuessedAgain() {
        // The computer chose the phone at 500; the phone said it left at 900.
        assertEquals(PairLink.Decision("", "", setOf("phone")), PairLink.decide("phone", 500L, claim("phone", active = false, at = 900), now))
        // With no choice, a guess must skip the phone that just left.
        assertEquals(PairLink.Decision("", "", setOf("phone")), PairLink.decide("", 0L, claim("phone", active = false, at = 900), now))
    }

    @Test
    fun aChoiceMadeAfterThePartnerLeftStandsAndAnotherPeersDepartureIsIrrelevant() {
        assertEquals(PairLink.Decision("phone", "", emptySet()), PairLink.decide("phone", 950L, claim("phone", active = false, at = 900), now))
        assertEquals(PairLink.Decision("amanda", "", emptySet()), PairLink.decide("amanda", 100L, claim("phone", active = false, at = 900), now))
    }

    @Test
    fun theLeaseIsTenMinutes() {
        assertTrue(PairLink.LEASE_MS == 10 * 60 * 1000L)
    }
}
