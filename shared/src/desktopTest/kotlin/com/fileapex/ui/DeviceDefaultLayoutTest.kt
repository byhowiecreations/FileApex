package com.fileapex.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.devices.resolveFreestyleNodePositions
import com.fileapex.ui.kinetic.kineticStaticNodePositions
import com.fileapex.ui.kinetic.resolveKineticNodePositions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDefaultLayoutTest {

    private val density = Density(1f)

    private fun makeRows(count: Int): List<DeviceListRow> =
        (0 until count).map { i ->
            DeviceListRow(
                deviceId = "dev-$i",
                deviceName = "Device $i",
                online = true,
                appVersion = "0.20.11"
            )
        }

    private fun normalizeAngleRad(angle: Double): Double {
        var a = angle % (2.0 * PI)
        if (a < 0) a += 2.0 * PI
        return a
    }

    @Test
    fun kineticUnassignedNodesAreEquallySpacedInStrictClockwiseOrderAndNeverOverlap() {
        val canvases = listOf(
            "desktop" to Pair(850f, 850f),
            "port-med" to Pair(673f, 916f),
            "land-exp" to Pair(1100f, 720f),
            "port-cmp" to Pair(390f, 780f)
        )
        for ((bucket, size) in canvases) {
            val (w, h) = size
            val centerX = w / 2f
            val centerY = h * 0.50f
            for (count in 1..8) {
                val rows = makeRows(count)
                val positions = resolveKineticNodePositions(
                    density = density,
                    deviceRows = rows,
                    widthPx = w,
                    heightPx = h,
                    layoutBucket = bucket,
                    persistedNodeOffsets = emptyMap()
                )
                assertEquals(count, positions.size)

                // Every node must stay inside the canvas and clear of the center hub
                positions.forEachIndexed { idx, pos ->
                    assertTrue(
                        "$bucket count=$count idx=$idx x=${pos.x} out of bounds (w=$w)",
                        pos.x in 48f..(w - 48f)
                    )
                    assertTrue(
                        "$bucket count=$count idx=$idx y=${pos.y} out of bounds (h=$h)",
                        pos.y in 48f..(h - 68f)
                    )
                    val hubDist = hypot((pos.x - centerX).toDouble(), (pos.y - centerY).toDouble())
                    assertTrue(
                        "$bucket count=$count idx=$idx too close to hub: $hubDist",
                        hubDist >= 115.0
                    )
                }

                // No two nodes may ever sit on top of each other
                for (i in 0 until count) {
                    for (j in i + 1 until count) {
                        val dist = hypot(
                            (positions[i].x - positions[j].x).toDouble(),
                            (positions[i].y - positions[j].y).toDouble()
                        )
                        assertTrue(
                            "$bucket count=$count nodes $i and $j overlap (dist=$dist)",
                            dist >= 90.0
                        )
                    }
                }

                // When count >= 3, angles around the hub must progress in strict clockwise order
                if (count >= 3) {
                    val angles = positions.map { pos ->
                        atan2((pos.y - centerY).toDouble(), (pos.x - centerX).toDouble())
                    }
                    val start = angles.first()
                    val deltas = angles.map { normalizeAngleRad(it - start) }
                    for (i in 1 until count) {
                        assertTrue(
                            "$bucket count=$count angles out of clockwise order at $i: $deltas",
                            deltas[i] > deltas[i - 1] + 0.25
                        )
                    }
                }
            }
        }
    }

    @Test
    fun kineticOverlappingStoredNodesAreSeparatedSoIconsNeverStack() {
        val rows = makeRows(8)
        // Simulate two pairs of nodes saved right on top of each other (like clamped cross-orientation fractions)
        val collidingOffsets = mapOf(
            kineticFractionKey("port-med", "dev-0") to (0.45f to 0.20f),
            kineticFractionKey("port-med", "dev-1") to (0.46f to 0.21f),
            kineticFractionKey("port-med", "dev-2") to (0.88f to 0.20f),
            kineticFractionKey("port-med", "dev-3") to (0.89f to 0.21f)
        )
        val positions = resolveKineticNodePositions(
            density = density,
            deviceRows = rows,
            widthPx = 673f,
            heightPx = 916f,
            layoutBucket = "port-med",
            persistedNodeOffsets = collidingOffsets
        )
        for (i in 0 until rows.size) {
            for (j in i + 1 until rows.size) {
                val dist = hypot(
                    (positions[i].x - positions[j].x).toDouble(),
                    (positions[i].y - positions[j].y).toDouble()
                )
                assertTrue(
                    "Nodes $i and $j stacked at ${positions[i]} vs ${positions[j]} (dist=$dist)",
                    dist >= 95.0
                )
            }
        }
    }

    @Test
    fun freestyleFormsCleanShapesForOneTwoThreeFourAndMoreDevices() {
        val w = 800f
        val h = 900f
        val buttonSize = 68f
        val optionsPos = Offset((w - 110f) / 2f, h - 16f - 42f - 8f)

        // 1 device: centered horizontally
        val one = resolveFreestyleNodePositions(
            density = density,
            deviceRows = makeRows(1),
            widthPx = w,
            heightPx = h,
            layoutScopePrefix = "port-med:",
            legacyScopePrefix = "exp:",
            persistedOffsets = emptyMap(),
            useTileRowFallback = false,
            optionsPos = optionsPos,
            pinnedActions = emptyMap()
        )
        val oneCenter = one.getValue("port-med:dev-0") + Offset(buttonSize / 2f, buttonSize / 2f)
        assertEquals(w / 2f, oneCenter.x, 2f)

        // 2 devices: clean horizontal pair at same Y, symmetric around center X
        val two = resolveFreestyleNodePositions(
            density = density,
            deviceRows = makeRows(2),
            widthPx = w,
            heightPx = h,
            layoutScopePrefix = "port-med:",
            legacyScopePrefix = "exp:",
            persistedOffsets = emptyMap(),
            useTileRowFallback = false,
            optionsPos = optionsPos,
            pinnedActions = emptyMap()
        )
        val c20 = two.getValue("port-med:dev-0") + Offset(buttonSize / 2f, buttonSize / 2f)
        val c21 = two.getValue("port-med:dev-1") + Offset(buttonSize / 2f, buttonSize / 2f)
        assertEquals(c20.y, c21.y, 2f)
        assertTrue(c20.x < w / 2f - 60f && c21.x > w / 2f + 60f)
        assertEquals(w - c20.x, c21.x, 2f)

        // 3 devices: clean upright triangle (1 top center, 2 bottom at equal Y and symmetric X)
        val three = resolveFreestyleNodePositions(
            density = density,
            deviceRows = makeRows(3),
            widthPx = w,
            heightPx = h,
            layoutScopePrefix = "port-med:",
            legacyScopePrefix = "exp:",
            persistedOffsets = emptyMap(),
            useTileRowFallback = false,
            optionsPos = optionsPos,
            pinnedActions = emptyMap()
        )
        val c30 = three.getValue("port-med:dev-0") + Offset(buttonSize / 2f, buttonSize / 2f)
        val c31 = three.getValue("port-med:dev-1") + Offset(buttonSize / 2f, buttonSize / 2f)
        val c32 = three.getValue("port-med:dev-2") + Offset(buttonSize / 2f, buttonSize / 2f)
        assertEquals(w / 2f, c30.x, 2f)
        assertTrue(c30.y < c31.y - 80f)
        assertEquals(c31.y, c32.y, 2f)
        assertEquals(w - c31.x, c32.x, 2f)

        // 4 devices: clean square (2 top corners at equal Y, 2 bottom corners at equal Y, matching X columns)
        val four = resolveFreestyleNodePositions(
            density = density,
            deviceRows = makeRows(4),
            widthPx = w,
            heightPx = h,
            layoutScopePrefix = "port-med:",
            legacyScopePrefix = "exp:",
            persistedOffsets = emptyMap(),
            useTileRowFallback = false,
            optionsPos = optionsPos,
            pinnedActions = emptyMap()
        )
        val c4 = (0 until 4).map { four.getValue("port-med:dev-$it") + Offset(buttonSize / 2f, buttonSize / 2f) }
        val sortedByY = c4.sortedBy { it.y }
        val topRow = sortedByY.take(2).sortedBy { it.x }
        val bottomRow = sortedByY.drop(2).sortedBy { it.x }
        assertEquals("Top corners of 4-device square must share Y", topRow[0].y, topRow[1].y, 2f)
        assertEquals("Bottom corners of 4-device square must share Y", bottomRow[0].y, bottomRow[1].y, 2f)
        assertEquals("Left corners of 4-device square must share X", topRow[0].x, bottomRow[0].x, 2f)
        assertEquals("Right corners of 4-device square must share X", topRow[1].x, bottomRow[1].x, 2f)
        val squareWidth = topRow[1].x - topRow[0].x
        val squareHeight = bottomRow[0].y - topRow[0].y
        assertTrue("Square sides must be well separated ($squareWidth x $squareHeight)", squareWidth >= 140f && squareHeight >= 140f)
        assertEquals("4-device formation should have equal width and height on roomy canvas", squareWidth, squareHeight, 4f)

        // 5..8 devices: clean circle/ellipse, zero overlaps, strict angular order
        for (count in 5..8) {
            val map = resolveFreestyleNodePositions(
                density = density,
                deviceRows = makeRows(count),
                widthPx = w,
                heightPx = h,
                layoutScopePrefix = "port-med:",
                legacyScopePrefix = "exp:",
                persistedOffsets = emptyMap(),
                useTileRowFallback = false,
                optionsPos = optionsPos,
                pinnedActions = emptyMap()
            )
            val centers = (0 until count).map { map.getValue("port-med:dev-$it") + Offset(buttonSize / 2f, buttonSize / 2f) }
            for (i in 0 until count) {
                for (j in i + 1 until count) {
                    val dist = hypot((centers[i].x - centers[j].x).toDouble(), (centers[i].y - centers[j].y).toDouble())
                    assertTrue("Freestyle count=$count nodes $i and $j overlap (dist=$dist)", dist >= 92.0)
                }
            }
        }
    }

    @Test
    fun freestyleIncrementalLoadRecomputesUnassignedNodesInsteadOfLatchingSingleNodeCenter() {
        val w = 800f
        val h = 900f
        val buttonSize = 68f
        val optionsPos = Offset((w - 110f) / 2f, h - 16f - 42f - 8f)

        val firstLoad = resolveFreestyleNodePositions(
            density = density,
            deviceRows = makeRows(1),
            widthPx = w,
            heightPx = h,
            layoutScopePrefix = "port-med:",
            legacyScopePrefix = "exp:",
            persistedOffsets = emptyMap(),
            useTileRowFallback = false,
            optionsPos = optionsPos,
            pinnedActions = emptyMap()
        )
        val firstDev0 = firstLoad.getValue("port-med:dev-0") + Offset(buttonSize / 2f, buttonSize / 2f)
        assertEquals(w / 2f, firstDev0.x, 2f)

        // When 3 more devices finish loading, dev-0 must move to a square corner rather than staying in the middle
        val fullLoad = resolveFreestyleNodePositions(
            density = density,
            deviceRows = makeRows(4),
            widthPx = w,
            heightPx = h,
            layoutScopePrefix = "port-med:",
            legacyScopePrefix = "exp:",
            persistedOffsets = emptyMap(),
            useTileRowFallback = false,
            optionsPos = optionsPos,
            pinnedActions = emptyMap()
        )
        val fullDev0 = fullLoad.getValue("port-med:dev-0") + Offset(buttonSize / 2f, buttonSize / 2f)
        assertTrue("dev-0 must move out of center X when 4 devices are present", abs(fullDev0.x - w / 2f) > 60f)
    }

    @Test
    fun partialUserMovedDevicesStayPutWhileUnassignedDevicesAvoidCollisions() {
        val w = 800f
        val h = 900f
        val buttonSize = 68f
        val rows = makeRows(6)

        // Kinetic: user places dev-4 directly on the default slot of dev-0
        val kineticDefaults = resolveKineticNodePositions(
            density = density,
            deviceRows = rows,
            widthPx = w,
            heightPx = h,
            layoutBucket = "port-med",
            persistedNodeOffsets = emptyMap()
        )
        val slot0 = kineticDefaults[0]
        val kineticPartial = resolveKineticNodePositions(
            density = density,
            deviceRows = rows,
            widthPx = w,
            heightPx = h,
            layoutBucket = "port-med",
            persistedNodeOffsets = mapOf(
                kineticFractionKey("port-med", "dev-4") to (slot0.x / w to slot0.y / h)
            )
        )
        assertEquals(slot0.x, kineticPartial[4].x, 1.5f)
        assertEquals(slot0.y, kineticPartial[4].y, 1.5f)
        for (i in 0 until rows.size) {
            for (j in i + 1 until rows.size) {
                val dist = hypot(
                    (kineticPartial[i].x - kineticPartial[j].x).toDouble(),
                    (kineticPartial[i].y - kineticPartial[j].y).toDouble()
                )
                assertTrue("Kinetic partial nodes $i and $j overlap (dist=$dist)", dist >= 95.0)
            }
        }

        // Freestyle: user places dev-3 directly on dev-0's default slot and pins an action on dev-1's slot
        val optionsPos = Offset((w - 110f) / 2f, h - 16f - 42f - 8f)
        val freestyleDefaults = resolveFreestyleNodePositions(
            density = density,
            deviceRows = rows,
            widthPx = w,
            heightPx = h,
            layoutScopePrefix = "port-med:",
            legacyScopePrefix = "exp:",
            persistedOffsets = emptyMap(),
            useTileRowFallback = false,
            optionsPos = optionsPos,
            pinnedActions = emptyMap()
        )
        val freeSlot0Center = freestyleDefaults.getValue("port-med:dev-0") + Offset(buttonSize / 2f, buttonSize / 2f)
        val freeSlot1Center = freestyleDefaults.getValue("port-med:dev-1") + Offset(buttonSize / 2f, buttonSize / 2f)
        val freestylePartial = resolveFreestyleNodePositions(
            density = density,
            deviceRows = rows,
            widthPx = w,
            heightPx = h,
            layoutScopePrefix = "port-med:",
            legacyScopePrefix = "exp:",
            persistedOffsets = mapOf(
                "port-med:dev-3" to (freeSlot0Center.x / w to freeSlot0Center.y / h)
            ),
            useTileRowFallback = false,
            optionsPos = optionsPos,
            pinnedActions = mapOf(
                "files" to (freeSlot1Center.x / w to freeSlot1Center.y / h)
            )
        )
        val centers = (0 until rows.size).map {
            freestylePartial.getValue("port-med:dev-$it") + Offset(buttonSize / 2f, buttonSize / 2f)
        }
        assertEquals(freeSlot0Center.x, centers[3].x, 1.5f)
        assertEquals(freeSlot0Center.y, centers[3].y, 1.5f)
        for (i in 0 until rows.size) {
            val pinDist = hypot((centers[i].x - freeSlot1Center.x).toDouble(), (centers[i].y - freeSlot1Center.y).toDouble())
            assertTrue("Freestyle node $i overlaps pinned action (dist=$pinDist)", pinDist >= 90.0)
            for (j in i + 1 until rows.size) {
                val dist = hypot((centers[i].x - centers[j].x).toDouble(), (centers[i].y - centers[j].y).toDouble())
                assertTrue("Freestyle partial nodes $i and $j overlap (dist=$dist)", dist >= 90.0)
            }
        }
    }

    @Test
    fun kineticUserPlacedNodesAdjustIntelligentlyAcrossResizeAndTransferBannerWithoutJumpingToDefaults() {
        val rows = makeRows(8)
        // User placed dev-0 at top center (x=0.5, y=0.24) and dev-1 at bottom center (x=0.5, y=0.82)
        val userOffsets = mapOf(
            kineticFractionKey("desktop", "dev-0") to (0.50f to 0.24f),
            kineticFractionKey("desktop", "dev-1") to (0.50f to 0.82f)
        )

        // 1. Initial window (850 x 860)
        val initial = resolveKineticNodePositions(
            density = density,
            deviceRows = rows,
            widthPx = 850f,
            heightPx = 860f,
            layoutBucket = "desktop",
            persistedNodeOffsets = userOffsets
        )
        // dev-0 should be at top center
        assertEquals(850f * 0.50f, initial[0].x, 2f)
        assertEquals(860f * 0.24f, initial[0].y, 2f)
        // dev-1 should be at bottom center
        assertEquals(850f * 0.50f, initial[1].x, 2f)
        assertEquals(860f * 0.82f, initial[1].y, 2f)

        // 2. Transfer banner appears (height decreases to 796)
        val withTransfer = resolveKineticNodePositions(
            density = density,
            deviceRows = rows,
            widthPx = 850f,
            heightPx = 796f,
            layoutBucket = "desktop",
            persistedNodeOffsets = userOffsets
        )
        // dev-0 MUST NOT jump to the default orbit angle (~1 o'clock); it must stay at top center scaled to new height
        assertEquals(850f * 0.50f, withTransfer[0].x, 2f)
        assertEquals(796f * 0.24f, withTransfer[0].y, 2f)
        // dev-1 must stay at bottom center scaled to new height
        assertEquals(850f * 0.50f, withTransfer[1].x, 2f)
        assertEquals(796f * 0.82f, withTransfer[1].y, 2f)

        // 3. Window resized to wide desktop (1200 x 800)
        val resized = resolveKineticNodePositions(
            density = density,
            deviceRows = rows,
            widthPx = 1200f,
            heightPx = 800f,
            layoutBucket = "desktop",
            persistedNodeOffsets = userOffsets
        )
        assertEquals(1200f * 0.50f, resized[0].x, 2f)
        assertEquals(800f * 0.24f, resized[0].y, 2f)
        assertEquals(1200f * 0.50f, resized[1].x, 2f)
        assertEquals(800f * 0.82f, resized[1].y, 2f)
    }

    @Test
    fun freestyleUserPlacedNodesAdjustIntelligentlyAcrossResizeAndTransferBanner() {
        val rows = makeRows(4)
        val buttonSize = 68f
        val userOffsets = mapOf(
            "desktop:dev-0" to (0.20f to 0.30f),
            "desktop:dev-1" to (0.80f to 0.30f)
        )
        val optionsPosBefore = Offset((850f - 110f) / 2f, 860f - 16f - 42f - 8f)

        // Initial
        val initial = resolveFreestyleNodePositions(
            density = density,
            deviceRows = rows,
            widthPx = 850f,
            heightPx = 860f,
            layoutScopePrefix = "desktop:",
            legacyScopePrefix = "exp:",
            persistedOffsets = userOffsets,
            useTileRowFallback = false,
            optionsPos = optionsPosBefore,
            pinnedActions = emptyMap()
        )
        val initC0 = initial.getValue("desktop:dev-0") + Offset(buttonSize / 2f, buttonSize / 2f)
        assertEquals(850f * 0.20f, initC0.x, 2f)
        assertEquals(860f * 0.30f, initC0.y, 2f)

        // Transfer banner appears (height = 796)
        val optionsPosAfter = Offset((850f - 110f) / 2f, 796f - 16f - 42f - 8f)
        val withTransfer = resolveFreestyleNodePositions(
            density = density,
            deviceRows = rows,
            widthPx = 850f,
            heightPx = 796f,
            layoutScopePrefix = "desktop:",
            legacyScopePrefix = "exp:",
            persistedOffsets = userOffsets,
            useTileRowFallback = false,
            optionsPos = optionsPosAfter,
            pinnedActions = emptyMap()
        )
        val transC0 = withTransfer.getValue("desktop:dev-0") + Offset(buttonSize / 2f, buttonSize / 2f)
        assertEquals(850f * 0.20f, transC0.x, 2f)
        assertEquals(796f * 0.30f, transC0.y, 2f)
    }
}
