package com.fileapex.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerPinHeaderTest {
    @Test
    fun thePinLeavesTheUrlAndKeepsTheOtherParameters() {
        val path = "/api/v1/files/list?path=%2Fsdcard&pin=12%2034&from=abc"
        assertEquals("12 34", PeerPinHeader.pinOf(path))
        assertEquals("/api/v1/files/list?path=%2Fsdcard&from=abc", PeerPinHeader.stripPin(path))
    }

    @Test
    fun aPathWithoutAPinIsUnchanged() {
        assertNull(PeerPinHeader.pinOf("/api/v1/health?from=abc"))
        assertEquals("/api/v1/health?from=abc", PeerPinHeader.stripPin("/api/v1/health?from=abc"))
        assertEquals("/api/v1/health", PeerPinHeader.stripPin("/api/v1/health"))
        assertEquals("/api/v1/x", PeerPinHeader.stripPin("/api/v1/x?pin=1"))
    }

    @Test
    fun lineBreaksCannotBeSmuggledIntoTheHeader() {
        assertEquals("1234", PeerPinHeader.pinOf("/x?pin=12%0D%0A34"))
    }
}
