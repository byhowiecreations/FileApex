package com.fileapex.presentation

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class TransferDropUriTest {
    @Test
    fun roundTripsNamesThatBreakAnUnencodedUri() {
        val path = "/Users/me/Reports & Notes/100% done?#1 café.txt"
        val uri = TransferDropUri.build("device-1", path, "100% done?#1 café.txt", 42L)
        val parsed = TransferDropUri.parse(uri)!!
        assertEquals("device-1", parsed.sourceDeviceId)
        assertEquals(path, parsed.path)
        assertEquals("100% done?#1 café.txt", parsed.name)
        assertEquals(42L, parsed.size)
        assertFalse(parsed.isLocal)
    }

    @Test
    fun localSourceIsMarkedLocal() {
        val parsed = TransferDropUri.parse(TransferDropUri.build(null, "/a/b.txt", "b.txt", 1L))!!
        assertTrue(parsed.isLocal)
        assertEquals("/a/b.txt", parsed.path)
    }

    @Test
    fun readsAnOldUnencodedUri() {
        val parsed = TransferDropUri.parse("fileapex-transfer://dev//sdcard/Download/a b.txt?name=a b.txt&size=7")!!
        assertEquals("/sdcard/Download/a b.txt", parsed.path)
        assertEquals("a b.txt", parsed.name)
        assertEquals(7L, parsed.size)
    }

    @Test
    fun missingNameFallsBackToThePathAndOtherTextIsNotAUri() {
        assertEquals("c.txt", TransferDropUri.parse("fileapex-transfer://dev//x/c.txt")!!.name)
        assertNull(TransferDropUri.parse("/plain/path.txt"))
    }

    @Test
    fun aStrayPercentIsKept() {
        assertEquals("/a/50%", TransferDropUri.parse("fileapex-transfer://d//a/50%?name=50%&size=1")!!.path)
    }
}
