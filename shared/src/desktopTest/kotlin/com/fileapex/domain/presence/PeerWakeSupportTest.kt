package com.fileapex.domain.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Tap to wake" is only honest for a device that can really be woken: an Android phone reachable by a cloud message. */
class PeerWakeSupportTest {
    @Test
    fun anAndroidPhoneThatACloudMessageCanReachCanBeWoken() {
        assertTrue(PeerWakeSupport.canWake(os = "android", platform = "", cloudReachable = true))
        assertTrue(PeerWakeSupport.canWake(os = "", platform = "Android", cloudReachable = true))
    }

    @Test
    fun anAndroidPhoneWithNoCloudPathCannotBeWoken() {
        assertFalse(PeerWakeSupport.canWake(os = "android", platform = "", cloudReachable = false))
    }

    @Test
    fun aComputerCannotBeWokenEvenWithACloudRecord() {
        listOf("windows", "macos", "linux", "desktop").forEach {
            assertFalse(it, PeerWakeSupport.canWake(os = it, platform = "", cloudReachable = true))
        }
        assertFalse(PeerWakeSupport.canWake(os = "", platform = "Windows", cloudReachable = true))
    }

    @Test
    fun aDockerNodeCannotBeWoken() {
        assertFalse(PeerWakeSupport.canWake(os = "docker", platform = "Docker", cloudReachable = true))
    }

    @Test
    fun anUnknownPlatformIsNotAssumedToBeAPhone() {
        assertFalse(PeerWakeSupport.canWake(os = "", platform = "", cloudReachable = true))
    }

    @Test
    fun aBuildWithoutGoogleNeverShowsTapToWake() {
        assertFalse(PeerWakeSupport.canWake(os = "android", platform = "", cloudReachable = true, thisBuildIsFdroid = true))
    }
}
