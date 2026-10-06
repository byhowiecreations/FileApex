package com.fileapex.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplicedLoopbackOriginTest {
    @Test
    fun loopbackOriginsAreNotTrusted() {
        assertTrue(isSplicedLoopbackOrigin("127.0.0.1"))
        assertTrue(isSplicedLoopbackOrigin("/127.0.0.1:52314"))
        assertTrue(isSplicedLoopbackOrigin("::1"))
        assertTrue(isSplicedLoopbackOrigin("[::1]:80"))
        assertTrue(isSplicedLoopbackOrigin("localhost"))
        assertFalse(isSplicedLoopbackOrigin("192.168.1.20"))
        assertFalse(isSplicedLoopbackOrigin("100.64.1.2"))
    }
}
