package com.fileapex.domain.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLinkStateTest {
    @Test
    fun theShownPartnerIsNeverSkippedBecauseItsCheckInCarriesTheLink() {
        assertTrue(PairLinkState.carriesLink("mac", shownId = "mac"))
    }

    @Test
    fun otherPeersMayBeSkippedAsBefore() {
        assertFalse(PairLinkState.carriesLink("amanda", shownId = "mac"))
    }

    @Test
    fun withNothingShownNoPeerCarriesTheLink() {
        assertFalse(PairLinkState.carriesLink("mac", shownId = ""))
        assertFalse(PairLinkState.carriesLink("", shownId = ""))
    }
}

class PairLinkReleaseTest {
    @Test
    fun aPeerThatIsNotTheChosenOneIsToldTheTimeOfTheChoice() {
        assertEquals(900L, PairLinkState.releaseTimeFor("amanda", choiceId = "fold", choiceAt = 900L))
    }

    @Test
    fun theChosenPeerIsNotReleased() {
        assertEquals(0L, PairLinkState.releaseTimeFor("fold", choiceId = "fold", choiceAt = 900L))
    }

    @Test
    fun withNoChoiceOrOnlyAGuessNobodyIsReleased() {
        assertEquals(0L, PairLinkState.releaseTimeFor("amanda", choiceId = "", choiceAt = 0L))
        assertEquals(0L, PairLinkState.releaseTimeFor("amanda", choiceId = "fold", choiceAt = 0L))
    }

    @Test
    fun aLostReleaseHealsFromTheNextCheckInAndAStaleOneChangesNothing() {
        val standing = LinkRecord("mac", true, 100L, 0L)
        assertEquals(LinkRecord("mac", false, 500L, 10_000L + PairLink.LEASE_MS), PairLink.merge(standing, "mac", PairLink.Signal(false, 500L), 10_000L))
        assertEquals(standing, PairLink.merge(standing, "mac", PairLink.Signal(false, 50L), 10_000L))
    }

    @Test
    fun thePhoneReleasesEveryComputerExceptTheOneItChose() {
        assertEquals(
            HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(false, 900L)),
            PhoneLink.releaseSignal("amanda", choiceId = "mac", choiceAt = 900L)
        )
        assertNull(PhoneLink.releaseSignal("mac", choiceId = "mac", choiceAt = 900L))
        assertNull(PhoneLink.releaseSignal("amanda", choiceId = "mac", choiceAt = 0L))
    }
}
