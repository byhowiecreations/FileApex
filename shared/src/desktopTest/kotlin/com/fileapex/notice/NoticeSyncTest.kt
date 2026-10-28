package com.fileapex.notice

import com.fileapex.notice.NoticeSamples.notice
import com.fileapex.notice.NoticeSamples.payload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeSyncTest {
    private val key = NoticeTestKey()
    private val keys = listOf(key.publicKey)
    private val store = NoticeSettingsStore(MapKvStore()).apply { markChoiceMade() }
    private val now = 1_800_000_000_000L
    private val url = "https://example.test/notice.json"

    private fun file(serial: Long, id: String = "a") = key.file(payload(serial, NoticeSamples.ISSUED, notice(id = id)))

    private fun check(transport: FakeTransport, manual: Boolean = false, at: Long = now) =
        runBlocking { NoticeSync.check(store, transport, url, keys, at, manual) }

    @Test
    fun aGoodFileIsStoredWithItsEtagAndSerial() {
        val transport = FakeTransport(mutableListOf(NoticeHttpResponse.Ok(file(3), "\"v3\"")))
        assertEquals(NoticeSyncOutcome.Updated, check(transport))
        assertEquals("\"v3\"", store.etag())
        assertEquals(3L, store.highestSerial())
        assertTrue(store.cachedFile() != null)
        assertEquals(now, store.lastSuccessMs())
    }

    @Test
    fun theEtagIsSentOnTheNextRequest() {
        val transport = FakeTransport(mutableListOf(NoticeHttpResponse.Ok(file(3), "\"v3\""), NoticeHttpResponse.NotModified))
        check(transport)
        check(transport, manual = true)
        assertEquals(listOf(url to null, url to "\"v3\""), transport.requests)
    }

    @Test
    fun aBadSignatureNeverSavesTheEtagOrReplacesTheGoodFile() {
        check(FakeTransport(mutableListOf(NoticeHttpResponse.Ok(file(3), "\"v3\""))))
        val forged = NoticeTestKey().file(payload(4, NoticeSamples.ISSUED, notice(id = "evil")))
        assertEquals(NoticeSyncOutcome.Rejected, check(FakeTransport(mutableListOf(NoticeHttpResponse.Ok(forged, "\"v4\""))), manual = true))
        assertEquals("\"v3\"", store.etag())
        assertEquals(3L, store.highestSerial())
        assertFalse(store.cachedFile()!!.decodeToString().contains("evil"))
    }

    @Test
    fun anOlderServedFileIsRefused() {
        check(FakeTransport(mutableListOf(NoticeHttpResponse.Ok(file(5), "\"v5\""))))
        assertEquals(NoticeSyncOutcome.Rejected, check(FakeTransport(mutableListOf(NoticeHttpResponse.Ok(file(4), "\"v4\""))), manual = true))
        assertEquals(5L, store.highestSerial())
    }

    @Test
    fun offlineAndMissingFilesKeepTheStoredFileSilently() {
        check(FakeTransport(mutableListOf(NoticeHttpResponse.Ok(file(3), "\"v3\""))))
        assertEquals(NoticeSyncOutcome.Failed, check(FakeTransport(mutableListOf(NoticeHttpResponse.Failed)), manual = true))
        assertEquals(3L, store.highestSerial())
        assertTrue(store.cachedFile() != null)
    }

    @Test
    fun nothingIsRequestedBeforeTheExplanationWasShown() {
        val fresh = NoticeSettingsStore(MapKvStore())
        val transport = FakeTransport(mutableListOf(NoticeHttpResponse.Ok(file(1), null)))
        assertEquals(NoticeSyncOutcome.Skipped, runBlocking { NoticeSync.check(fresh, transport, url, keys, now, manual = true) })
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun turnedOffNeverChecksEvenWhenManual() {
        store.setEnabled(false)
        val transport = FakeTransport(mutableListOf(NoticeHttpResponse.Ok(file(1), null)))
        assertEquals(NoticeSyncOutcome.Skipped, check(transport, manual = true))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun checksAreSpacedSixHoursAfterSuccessAndFifteenMinutesAfterAFailure() {
        val ok = FakeTransport(mutableListOf(NoticeHttpResponse.NotModified))
        assertEquals(NoticeSyncOutcome.NotModified, check(ok))
        assertEquals(NoticeSyncOutcome.Throttled, check(ok, at = now + NoticeSync.MIN_SUCCESS_INTERVAL_MS - 1))
        assertEquals(NoticeSyncOutcome.NotModified, check(ok, at = now + NoticeSync.MIN_SUCCESS_INTERVAL_MS))
        assertEquals(2, ok.requests.size)

        val other = NoticeSettingsStore(MapKvStore()).apply { markChoiceMade() }
        val failing = FakeTransport(mutableListOf(NoticeHttpResponse.Failed))
        fun again(at: Long) = runBlocking { NoticeSync.check(other, failing, url, keys, at, manual = false) }
        assertEquals(NoticeSyncOutcome.Failed, again(now))
        assertEquals(NoticeSyncOutcome.Throttled, again(now + NoticeSync.MIN_ATTEMPT_INTERVAL_MS - 1))
        assertEquals(NoticeSyncOutcome.Failed, again(now + NoticeSync.MIN_ATTEMPT_INTERVAL_MS))
    }

    @Test
    fun aManualCheckIgnoresTheSpacing() {
        val transport = FakeTransport(mutableListOf(NoticeHttpResponse.NotModified))
        check(transport)
        assertEquals(NoticeSyncOutcome.NotModified, check(transport, manual = true, at = now + 1))
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun theOfficialUrlIsTheGitHubRawFileAndNoMirror() {
        assertEquals("https://raw.githubusercontent.com/byhowiecreations/FileApex/main/notice/notice.json", NoticeSync.OFFICIAL_URL)
        assertNull(Regex("pages\\.dev").find(NoticeSync.OFFICIAL_URL))
    }
}
