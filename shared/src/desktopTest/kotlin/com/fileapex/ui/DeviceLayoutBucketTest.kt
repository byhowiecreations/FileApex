package com.fileapex.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLayoutBucketTest {

    @Test
    fun desktopUsesSingleBucketAcrossWindowSizesAndOrientations() {
        val narrowPort = deviceLayoutBucket(width = 500.dp, height = 700.dp, isDesktop = true)
        val squarePort = deviceLayoutBucket(width = 850.dp, height = 860.dp, isDesktop = true)
        val squareLand = deviceLayoutBucket(width = 850.dp, height = 796.dp, isDesktop = true)
        val wideLand = deviceLayoutBucket(width = 1200.dp, height = 800.dp, isDesktop = true)
        assertEquals("desktop", narrowPort)
        assertEquals("desktop", squarePort)
        assertEquals("desktop", squareLand)
        assertEquals("desktop", wideLand)
    }

    @Test
    fun mobileDistinguishesOuterAndInnerDisplaysForPortraitAndLandscape() {
        val outerPort = deviceLayoutBucket(width = 390.dp, height = 844.dp, isDesktop = false)
        val outerLand = deviceLayoutBucket(width = 844.dp, height = 390.dp, isDesktop = false)
        val innerPort = deviceLayoutBucket(width = 720.dp, height = 840.dp, isDesktop = false)
        val innerLand = deviceLayoutBucket(width = 960.dp, height = 720.dp, isDesktop = false)
        assertEquals("outer-port", outerPort)
        assertEquals("outer-land", outerLand)
        assertEquals("inner-port", innerPort)
        assertEquals("inner-land", innerLand)
    }

    @Test
    fun desktopRecoversPositionsAcrossWindowResizesAndTransferBanner() {
        val offsets = mapOf(
            kineticFractionKey("desktop", "dev-0") to (0.50f to 0.24f),
            kineticFractionKey("desktop", "dev-1") to (0.50f to 0.82f)
        )
        // Before transfer bar
        val beforeBanner = kineticStoredNode(
            offsets = offsets,
            bucket = "desktop",
            deviceId = "dev-0",
            widthPx = 850f,
            heightPx = 860f,
            marginPx = 40f,
            topMarginPx = 40f
        )
        assertEquals(0.50f, beforeBanner!!.x, 0.0001f)
        assertEquals(0.24f, beforeBanner.y, 0.0001f)

        // After transfer banner appears (height decreases from 860 to 796)
        val afterBanner = kineticStoredNode(
            offsets = offsets,
            bucket = "desktop",
            deviceId = "dev-0",
            widthPx = 850f,
            heightPx = 796f,
            marginPx = 40f,
            topMarginPx = 40f
        )
        assertEquals(0.50f, afterBanner!!.x, 0.0001f)
        assertEquals(0.24f, afterBanner.y, 0.0001f)

        // Window resize to wide
        val wideWindow = kineticStoredNode(
            offsets = offsets,
            bucket = "desktop",
            deviceId = "dev-1",
            widthPx = 1400f,
            heightPx = 900f,
            marginPx = 40f,
            topMarginPx = 40f
        )
        assertEquals(0.50f, wideWindow!!.x, 0.0001f)
        assertEquals(0.82f, wideWindow.y, 0.0001f)
    }

    @Test
    fun desktopFallsBackToMigratedLandscapeOrPortraitKeys() {
        val migratedOffsets = mapOf(
            kineticFractionKey("land-cmp", "fold") to (0.75f to 0.35f)
        )
        val node = kineticStoredNode(
            offsets = migratedOffsets,
            bucket = "desktop",
            deviceId = "fold",
            widthPx = 1000f,
            heightPx = 800f,
            marginPx = 40f,
            topMarginPx = 40f
        )
        assertEquals(0.75f, node!!.x, 0.0001f)
        assertEquals(0.35f, node.y, 0.0001f)
        assertTrue(node.fractional)
    }

    @Test
    fun desktopFallsBackToLandCmpBeforeLandExp() {
        val migratedOffsets = mapOf(
            kineticFractionKey("land-cmp", "fold") to (0.87f to 0.35f),
            kineticFractionKey("land-exp", "fold") to (0.28f to 0.20f)
        )
        val node = kineticStoredNode(
            offsets = migratedOffsets,
            bucket = "desktop",
            deviceId = "fold",
            widthPx = 1000f,
            heightPx = 800f,
            marginPx = 40f,
            topMarginPx = 40f
        )
        assertEquals(0.87f, node!!.x, 0.0001f)
        assertEquals(0.35f, node.y, 0.0001f)
    }

    @Test
    fun foldPortraitAndLandscapeDoNotShareABucket() {
        val portrait = deviceLayoutBucket(width = 720.dp, height = 840.dp, isDesktop = false)
        val landscape = deviceLayoutBucket(width = 960.dp, height = 720.dp, isDesktop = false)
        assertEquals("inner-port", portrait)
        assertEquals("inner-land", landscape)
        assertFalse(portrait == landscape)
    }

    @Test
    fun portraitWithoutItsOwnFractionStaysUnassignedWhenLandscapeWasMoved() {
        val offsets = mapOf(
            kineticFractionKey("inner-land", "fold") to (0.82f to 0.2f),
            "pos:exp:fold" to (1800f to 240f),
            "pos:fold" to (1800f to 240f)
        )
        val portrait = kineticStoredNode(
            offsets = offsets,
            bucket = "inner-port",
            deviceId = "fold",
            widthPx = 1600f,
            heightPx = 2000f,
            marginPx = 40f,
            topMarginPx = 40f
        )
        assertNull(portrait)

        val landscape = kineticStoredNode(
            offsets = offsets,
            bucket = "inner-land",
            deviceId = "fold",
            widthPx = 2200f,
            heightPx = 1600f,
            marginPx = 40f,
            topMarginPx = 40f
        )
        assertEquals(0.82f, landscape!!.x, 0.0001f)
        assertEquals(0.2f, landscape.y, 0.0001f)
        assertTrue(landscape.fractional)
    }

    @Test
    fun legacyLandscapePixelRecordAppliesOnlyWhenNoFractionExistsAndBucketIsLandscape() {
        val offsets = mapOf("pos:exp:fold" to (1800f to 240f))
        val fits = kineticStoredNode(
            offsets, "inner-land", "fold",
            widthPx = 2200f, heightPx = 1600f, marginPx = 40f, topMarginPx = 40f
        )
        assertEquals(1800f, fits!!.x)
        assertFalse(fits.fractional)
        val clipped = kineticStoredNode(
            offsets, "inner-land", "fold",
            widthPx = 900f, heightPx = 700f, marginPx = 40f, topMarginPx = 40f
        )
        assertEquals(860f, clipped!!.x)
        assertEquals(240f, clipped.y)
        assertFalse(clipped.fractional)

        val portrait = kineticStoredNode(
            offsets, "inner-port", "fold",
            widthPx = 900f, heightPx = 1200f, marginPx = 40f, topMarginPx = 40f
        )
        assertNull(portrait)
    }

    @Test
    fun fractionAloneStillPlacesTheNode() {
        val offsets = mapOf(kineticFractionKey("inner-land", "fold") to (0.82f to 0.2f))
        val node = kineticStoredNode(
            offsets, "inner-land", "fold",
            widthPx = 2200f, heightPx = 1600f, marginPx = 40f, topMarginPx = 40f
        )
        assertEquals(0.82f, node!!.x)
        assertTrue(node.fractional)
    }

    @Test
    fun bucketFractionWinsOverStalePixelRecordsFromOtherBuckets() {
        val offsets = mapOf(
            kineticFractionKey("inner-port", "fold") to (0.25f to 0.65f),
            "pos:exp:fold" to (640f to 180f),
            "pos:fold" to (4000f to 4000f)
        )
        val node = kineticStoredNode(
            offsets, "inner-port", "fold",
            widthPx = 1000f, heightPx = 800f, marginPx = 40f, topMarginPx = 40f
        )
        assertEquals(0.25f, node!!.x, 0.0001f)
        assertEquals(0.65f, node.y, 0.0001f)
        assertTrue(node.fractional)
    }
}
