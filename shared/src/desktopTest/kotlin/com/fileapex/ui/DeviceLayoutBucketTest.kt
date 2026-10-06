package com.fileapex.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLayoutBucketTest {

    @Test
    fun foldPortraitAndLandscapeDoNotShareABucket() {
        val portrait = deviceLayoutBucket(width = 720.dp, height = 840.dp)
        val landscape = deviceLayoutBucket(width = 960.dp, height = 720.dp)
        assertEquals("port-med", portrait)
        assertEquals("land-exp", landscape)
        assertFalse(portrait == landscape)
    }

    @Test
    fun portraitWithoutItsOwnFractionUsesClosestAbsolutePixels() {
        val offsets = mapOf(
            kineticFractionKey("land-exp", "fold") to (0.82f to 0.2f),
            "pos:exp:fold" to (1800f to 240f)
        )
        val portrait = kineticStoredNode(
            offsets = offsets,
            bucket = "port-med",
            deviceId = "fold",
            widthPx = 1600f,
            heightPx = 2000f,
            marginPx = 40f,
            topMarginPx = 40f
        )
        assertEquals(1560f, portrait!!.x)
        assertEquals(240f, portrait.y)
        assertFalse(portrait.fractional)
        val landscape = kineticStoredNode(
            offsets = offsets,
            bucket = "land-exp",
            deviceId = "fold",
            widthPx = 2200f,
            heightPx = 1600f,
            marginPx = 40f,
            topMarginPx = 40f
        )
        assertEquals(1800f, landscape!!.x)
        assertEquals(240f, landscape.y)
        assertFalse(landscape.fractional)
    }

    @Test
    fun absolutePixelsSnapToTheNearestOnCanvasPixel() {
        val offsets = mapOf("pos:exp:fold" to (1800f to 240f))
        val fits = kineticStoredNode(
            offsets, "land-exp", "fold",
            widthPx = 2200f, heightPx = 1600f, marginPx = 40f, topMarginPx = 40f
        )
        assertEquals(1800f, fits!!.x)
        assertFalse(fits.fractional)
        val clipped = kineticStoredNode(
            offsets, "land-med", "fold",
            widthPx = 900f, heightPx = 700f, marginPx = 40f, topMarginPx = 40f
        )
        assertEquals(860f, clipped!!.x)
        assertEquals(240f, clipped.y)
        assertFalse(clipped.fractional)
    }

    @Test
    fun fractionAloneStillPlacesTheNode() {
        val offsets = mapOf(kineticFractionKey("land-exp", "fold") to (0.82f to 0.2f))
        val node = kineticStoredNode(
            offsets, "land-exp", "fold",
            widthPx = 2200f, heightPx = 1600f, marginPx = 40f, topMarginPx = 40f
        )
        assertEquals(0.82f, node!!.x)
        assertTrue(node.fractional)
    }

    @Test
    fun driftedFractionRedrawsAtTheClosestAbsolutePixel() {
        val offsets = mapOf(
            kineticFractionKey("port-med", "fold") to (0.12f to 0.9f),
            "pos:exp:fold" to (640f to 180f),
            "pos:fold" to (4000f to 4000f)
        )
        val node = kineticStoredNode(
            offsets, "port-med", "fold",
            widthPx = 1000f, heightPx = 800f, marginPx = 40f, topMarginPx = 40f
        )
        assertEquals(640f, node!!.x)
        assertEquals(180f, node.y)
        assertFalse(node.fractional)
    }
}
