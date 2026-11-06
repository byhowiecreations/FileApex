package com.fileapex.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IrohRelaySettingTest {
    private val now = 10_000_000L
    private fun setting(enabled: Boolean = true, url: String = "https://relay.example.com", token: String = "t", at: Long) =
        IrohRelaySetting(enabled, url, token, at)

    private val none = setting(enabled = false, url = "", token = "", at = 0)

    @Test
    fun anOlderOrEqualChangeIsNeverAdopted() {
        val local = setting(at = now - 100)
        assertNull(IrohRelaySetting.adopt(local, setting(url = "https://other.example.com", at = now - 200), now))
        assertNull(IrohRelaySetting.adopt(local, local, now))
    }

    @Test
    fun aNewerChangeIsAdoptedWithItsOwnTimestamp() {
        val adopted = IrohRelaySetting.adopt(none, setting(at = now - 50), now)
        assertNotNull(adopted)
        assertEquals(now - 50, adopted!!.changedAtMs)
        assertEquals("https://relay.example.com", adopted.url)
        assertEquals("t", adopted.token)
    }

    @Test
    fun switchingTheRelayOffSpreadsLikeAnyOtherChange() {
        val local = setting(at = now - 100)
        val adopted = IrohRelaySetting.adopt(local, setting(enabled = false, url = "", token = "", at = now - 10), now)
        assertNotNull(adopted)
        assertEquals(false, adopted!!.enabled)
    }

    @Test
    fun aTimestampFromTheFutureIsRefusedSoABadClockCannotLockOutLaterChanges() {
        assertNull(IrohRelaySetting.adopt(none, setting(at = now + 11 * 60_000L), now))
        assertNotNull(IrohRelaySetting.adopt(none, setting(at = now + 9 * 60_000L), now))
    }

    @Test
    fun aMissingTimestampIsRefused() {
        assertNull(IrohRelaySetting.adopt(none, setting(at = 0), now))
        assertNull(IrohRelaySetting.adopt(none, setting(at = -5), now))
    }

    @Test
    fun anEnabledRelayNeedsAValidHttpsHostName() {
        for (url in listOf("", "http://relay.example.com", "https://203.0.113.5", "https://localhost", "https://a.example.com/path")) {
            assertNull(url, IrohRelaySetting.adopt(none, setting(url = url, at = now - 1), now))
        }
    }

    @Test
    fun theUrlIsStoredInItsCheckedForm() {
        val adopted = IrohRelaySetting.adopt(none, setting(url = " https://Relay.Example.com/ ", at = now - 1), now)
        assertEquals("https://relay.example.com", adopted!!.url)
    }

    @Test
    fun anOverlongOrOddTokenIsRefused() {
        assertNull(IrohRelaySetting.adopt(none, setting(token = "x".repeat(257), at = now - 1), now))
        assertNull(IrohRelaySetting.adopt(none, setting(token = "a b", at = now - 1), now))
        assertNull(IrohRelaySetting.adopt(none, setting(token = "a\nb", at = now - 1), now))
        assertNotNull(IrohRelaySetting.adopt(none, setting(token = "x".repeat(256), at = now - 1), now))
        assertNotNull(IrohRelaySetting.adopt(none, setting(token = "", at = now - 1), now))
    }

    @Test
    fun twoDevicesWithTheSameTimestampConvergeOnOneValue() {
        val a = setting(url = "https://a.example.com", at = now - 5)
        val b = setting(url = "https://b.example.com", at = now - 5)
        val aTakesB = IrohRelaySetting.adopt(a, b, now) != null
        val bTakesA = IrohRelaySetting.adopt(b, a, now) != null
        assertEquals("exactly one side must give way", true, aTakesB != bTakesA)
    }
}
