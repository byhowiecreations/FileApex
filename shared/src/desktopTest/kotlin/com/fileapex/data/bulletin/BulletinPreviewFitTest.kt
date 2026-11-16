package com.fileapex.data.bulletin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinPreviewFitTest {
    /** A fake encoder whose output size grows with the square of the edge and with the quality. */
    private fun encoder(bytesAtFull: Int): (Int, Int) -> ByteArray = { edge, quality ->
        ByteArray((bytesAtFull.toLong() * edge * edge / (320L * 320L) * quality / 100L).toInt().coerceAtLeast(1))
    }

    @Test
    fun anEasyImageKeepsTheFullEdgeAndFirstQuality() {
        val result = BulletinPreviewFit.encode(maxBytes = 64 * 1024, encode = encoder(bytesAtFull = 40 * 1024))
        assertNotNull(result)
        assertEquals(320, result!!.edge)
        assertEquals(75, result.quality)
    }

    @Test
    fun aHardImageLowersTheQualityBeforeTheSize() {
        val result = BulletinPreviewFit.encode(maxBytes = 64 * 1024, encode = encoder(bytesAtFull = 100 * 1024))!!
        assertEquals(320, result.edge)
        assertTrue(result.quality < 75)
    }

    @Test
    fun aVeryHardImageIsShrunkSoThereIsAlwaysAPreview() {
        val result = BulletinPreviewFit.encode(maxBytes = 64 * 1024, encode = encoder(bytesAtFull = 600 * 1024))
        assertNotNull("every image must get a preview, not only the easy ones", result)
        assertTrue(result!!.bytes.size <= 64 * 1024)
        assertTrue(result.edge < 320)
    }
}
