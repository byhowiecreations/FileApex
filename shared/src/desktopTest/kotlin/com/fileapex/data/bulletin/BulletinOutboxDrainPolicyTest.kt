package com.fileapex.data.bulletin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinOutboxDrainPolicyTest {
    @Test
    fun peerAttemptStillNeedsSupportEndpointAndOnline() {
        assertTrue(BulletinOutboxDrainPolicy.shouldAttemptPeer(true, "172.16.16.88", 8080, true))
        assertFalse(BulletinOutboxDrainPolicy.shouldAttemptPeer(true, "172.16.16.88", 8080, false))
        assertFalse(BulletinOutboxDrainPolicy.shouldAttemptPeer(false, "172.16.16.88", 8080, true))
        assertFalse(BulletinOutboxDrainPolicy.shouldAttemptPeer(true, "", 8080, true))
    }
}
