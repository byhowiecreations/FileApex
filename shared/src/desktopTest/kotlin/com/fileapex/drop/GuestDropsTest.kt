package com.fileapex.drop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDropsTest {
    private var clock = 1_000_000L
    private val drops = GuestDrops(now = { clock }, maxLive = 3)

    private fun create(ttl: Long = 10 * 60_000L) = checkNotNull(drops.create("/tmp/a.bin", "a.bin", 100L, ttl))

    private fun granted(id: String, secret: String): DropLease =
        (drops.begin(id, secret) as DropBegin.Granted).lease

    private fun refused(id: String, secret: String): DropRefusal =
        (drops.begin(id, secret) as DropBegin.Refused).reason

    @Test fun theRightSecretStartsADownload() {
        val d = create()
        val lease = granted(d.id, d.secret)
        assertEquals("/tmp/a.bin", lease.path)
        assertEquals(100L, lease.sizeBytes)
        assertEquals("a.bin", lease.name)
    }

    @Test fun aWrongSecretAndAnUnknownIdLookTheSame() {
        val d = create()
        assertEquals(DropRefusal.Denied, refused(d.id, "0".repeat(32)))
        assertEquals(DropRefusal.Denied, refused("f".repeat(32), d.secret))
    }

    @Test fun theSecretIsNotKeptByTheRegistry() {
        val d = create()
        assertFalse(drops.list().joinToString { it.toString() }.contains(d.secret))
    }

    @Test fun onlyOneDownloaderAtATime() {
        val d = create()
        val first = granted(d.id, d.secret)
        assertEquals(DropRefusal.Busy, refused(d.id, d.secret))
        first.release()
        granted(d.id, d.secret)
    }

    @Test fun aFinishedDownloadExpiresTheLinkAtOnce() {
        val d = create()
        val lease = granted(d.id, d.secret)
        lease.complete()
        assertEquals(DropRefusal.Used, refused(d.id, d.secret))
        assertTrue(drops.list().isEmpty())
    }

    @Test fun aDownloadThatDroppedBeforeTheEndLeavesTheLinkUsable() {
        val d = create()
        granted(d.id, d.secret).release()
        granted(d.id, d.secret)
    }

    @Test fun releaseAfterCompleteDoesNotRevive() {
        val d = create()
        val lease = granted(d.id, d.secret)
        lease.complete()
        lease.release()
        assertEquals(DropRefusal.Used, refused(d.id, d.secret))
    }

    @Test fun theHardLimitEndsAnUnopenedLink() {
        val d = create(ttl = 5 * 60_000L)
        clock += 5 * 60_000L + 1
        assertEquals(DropRefusal.Expired, refused(d.id, d.secret))
    }

    @Test fun theHardLimitEndsATransferThatIsStillRunning() {
        val d = create(ttl = 5 * 60_000L)
        val lease = granted(d.id, d.secret)
        assertFalse(lease.expired())
        clock += 5 * 60_000L + 1
        assertTrue(lease.expired())
    }

    @Test fun aLimitIsKeptBetweenOneMinuteAndOneDay() {
        assertEquals(clock + 60_000L, checkNotNull(drops.create("/p", "p", 1L, 1L)).expiresAtMs)
        assertEquals(clock + 24 * 3_600_000L, checkNotNull(drops.create("/p", "p", 1L, 99 * 3_600_000L)).expiresAtMs)
    }

    @Test fun revokingStopsNewAndRunningDownloads() {
        val d = create()
        val lease = granted(d.id, d.secret)
        assertTrue(drops.revoke(d.id))
        assertTrue(lease.expired())
        assertEquals(DropRefusal.Denied, refused(d.id, d.secret))
        assertFalse(drops.revoke(d.id))
    }

    @Test fun fiveWrongSecretsCloseTheLinkEvenForTheRightOne() {
        val d = create()
        repeat(5) { refused(d.id, "1".repeat(32)) }
        assertEquals(DropRefusal.Denied, refused(d.id, d.secret))
    }

    @Test fun aBusyRefusalIsNotCountedAsAFailure() {
        val d = create()
        val lease = granted(d.id, d.secret)
        repeat(10) { assertEquals(DropRefusal.Busy, refused(d.id, d.secret)) }
        lease.release()
        granted(d.id, d.secret)
    }

    @Test fun theNumberOfLiveLinksIsCapped() {
        repeat(3) { create() }
        assertNull(drops.create("/tmp/b.bin", "b.bin", 1L, 60_000L))
    }

    @Test fun expiredLinksFreeTheirPlace() {
        repeat(3) { create(ttl = 60_000L) }
        clock += 61_000L
        assertTrue(drops.create("/tmp/b.bin", "b.bin", 1L, 60_000L) != null)
    }

    @Test fun everyLinkGetsItsOwnIdAndSecret() {
        val a = create()
        val b = create()
        assertNotEquals(a.id, b.id)
        assertNotEquals(a.secret, b.secret)
        assertTrue(Regex("[0-9a-f]{32}").matches(a.id))
        assertTrue(Regex("[0-9a-f]{32}").matches(a.secret))
    }
}
