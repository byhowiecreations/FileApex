package com.fileapex.drop

import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.notice.MapKvStore
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLinkConsentTest {
    @Test fun nothingAnsweredYetMeansAskFirstButOfferTheAction() {
        assertTrue(GuestLinkConsent.Unasked.mustAsk)
        assertTrue(GuestLinkConsent.Unasked.offered)
        assertFalse(GuestLinkConsent.Unasked.allowed)
    }

    @Test fun acceptedAllowsAndNeverAsksAgain() {
        assertTrue(GuestLinkConsent.Accepted.allowed)
        assertTrue(GuestLinkConsent.Accepted.offered)
        assertFalse(GuestLinkConsent.Accepted.mustAsk)
    }

    @Test fun declinedHidesTheActionAndAllowsNothing() {
        assertFalse(GuestLinkConsent.Declined.offered)
        assertFalse(GuestLinkConsent.Declined.allowed)
        assertFalse(GuestLinkConsent.Declined.mustAsk)
    }

    @Test fun storedNumbersMapBackAndUnknownOnesAskAgain() {
        GuestLinkConsent.entries.forEach { assertEquals(it, GuestLinkConsent.fromStored(it.stored)) }
        assertEquals(GuestLinkConsent.Unasked, GuestLinkConsent.fromStored(99))
        assertEquals(GuestLinkConsent.Unasked, GuestLinkConsent.fromStored(-1))
    }

    @Test fun theAnswerSurvivesARestartAndAnUpdate() {
        val store = MapKvStore()
        assertEquals(GuestLinkConsent.Unasked, GuestLinkConsent.fromStored(BaseAppSettings(store).guestLinkConsent.value))
        BaseAppSettings(store).setGuestLinkConsent(GuestLinkConsent.Accepted.stored)
        assertEquals(GuestLinkConsent.Accepted, GuestLinkConsent.fromStored(BaseAppSettings(store).guestLinkConsent.value))
        BaseAppSettings(store).setGuestLinkConsent(GuestLinkConsent.Declined.stored)
        assertEquals(GuestLinkConsent.Declined, GuestLinkConsent.fromStored(BaseAppSettings(store).guestLinkConsent.value))
    }

    @Test fun theSettingTellsItsListenersWhenTheAnswerChanges() {
        val settings = BaseAppSettings(MapKvStore())
        val seen = mutableListOf<Int>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val job = scope.launch { settings.guestLinkConsent.collect { seen += it } }
        settings.setGuestLinkConsent(GuestLinkConsent.Accepted.stored)
        settings.setGuestLinkConsent(GuestLinkConsent.Declined.stored)
        job.cancel()
        assertEquals(listOf(0, 1, 2), seen)
    }
}
