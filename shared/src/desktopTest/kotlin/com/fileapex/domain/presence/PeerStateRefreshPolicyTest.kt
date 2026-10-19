package com.fileapex.domain.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerStateRefreshPolicyTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun neverFetchedPeerIsFetched() {
        assertTrue(PeerStateRefreshPolicy.shouldFetch(peerVersion = 5L, seenVersion = null, ageMs = null, missingMetadata = false))
    }

    @Test
    fun unchangedVersionIsNotFetchedAgainWithinTheSafetyWindow() {
        assertFalse(PeerStateRefreshPolicy.shouldFetch(peerVersion = 5L, seenVersion = 5L, ageMs = 2 * hour, missingMetadata = false))
    }

    @Test
    fun changedVersionIsFetchedAtOnce() {
        assertTrue(PeerStateRefreshPolicy.shouldFetch(peerVersion = 6L, seenVersion = 5L, ageMs = minute, missingMetadata = false))
    }

    @Test
    fun unchangedVersionIsStillRefreshedAfterTheSafetyWindow() {
        assertTrue(PeerStateRefreshPolicy.shouldFetch(peerVersion = 5L, seenVersion = 5L, ageMs = 7 * hour, missingMetadata = false))
    }

    @Test
    fun olderBuildWithoutAVersionKeepsTheFifteenMinuteRefresh() {
        assertFalse(PeerStateRefreshPolicy.shouldFetch(peerVersion = null, seenVersion = null, ageMs = 10 * minute, missingMetadata = false))
        assertTrue(PeerStateRefreshPolicy.shouldFetch(peerVersion = null, seenVersion = null, ageMs = 16 * minute, missingMetadata = false))
    }

    @Test
    fun missingMetadataRetriesEveryMinuteEvenWhenTheVersionIsUnchanged() {
        assertFalse(PeerStateRefreshPolicy.shouldFetch(peerVersion = 5L, seenVersion = 5L, ageMs = 30_000L, missingMetadata = true))
        assertTrue(PeerStateRefreshPolicy.shouldFetch(peerVersion = 5L, seenVersion = 5L, ageMs = 61_000L, missingMetadata = true))
    }
}
