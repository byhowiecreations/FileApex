package com.fileapex.domain.devices

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Placing a picked picture inside the round device icon. The icon is a unit square. At zoom 1 the picture covers it,
 * smaller zoom shrinks the picture so it can sit inside the safe ring, larger zoom crops into it.
 */
class DeviceIconCropTest {
    private fun assertPlacement(expected: DeviceIconCrop.Placement, actual: DeviceIconCrop.Placement) {
        assertEquals("left", expected.left, actual.left, 0.001f)
        assertEquals("top", expected.top, actual.top, 0.001f)
        assertEquals("width", expected.width, actual.width, 0.001f)
        assertEquals("height", expected.height, actual.height, 0.001f)
    }

    @Test
    fun `at zoom one a wide picture covers the icon and is centered`() {
        assertPlacement(DeviceIconCrop.Placement(-0.3f, 0f, 1.6f, 1f), DeviceIconCrop.placement(800, 500, 1f, Offset.Zero))
    }

    @Test
    fun `zooming out shrinks the picture so it can sit inside the icon`() {
        assertPlacement(DeviceIconCrop.Placement(0.1f, 0.25f, 0.8f, 0.5f), DeviceIconCrop.placement(800, 500, 0.5f, Offset.Zero))
    }

    @Test
    fun `a picture smaller than the icon moves freely but stays inside it`() {
        val limited = DeviceIconCrop.clampOffset(Offset(9f, -9f), sourceWidth = 800, sourceHeight = 500, zoom = 0.5f)
        assertEquals(0.1f, limited.x, 0.001f)
        assertEquals(-0.25f, limited.y, 0.001f)
        assertPlacement(DeviceIconCrop.Placement(0.2f, 0f, 0.8f, 0.5f), DeviceIconCrop.placement(800, 500, 0.5f, Offset(9f, -9f)))
    }

    @Test
    fun `a square picture can be shrunk and then dragged`() {
        val moved = DeviceIconCrop.placement(500, 500, 0.5f, Offset(0.2f, -0.1f))
        assertPlacement(DeviceIconCrop.Placement(0.45f, 0.15f, 0.5f, 0.5f), moved)
    }

    @Test
    fun `a picture larger than the icon always covers it`() {
        val limited = DeviceIconCrop.clampOffset(Offset(9f, -9f), sourceWidth = 800, sourceHeight = 500, zoom = 2f)
        assertEquals(1.1f, limited.x, 0.001f)
        assertEquals(-0.5f, limited.y, 0.001f)
        val p = DeviceIconCrop.placement(800, 500, 2f, Offset(9f, -9f))
        assertEquals(true, p.left <= 0f && p.left + p.width >= 1f && p.top <= 0f && p.top + p.height >= 1f)
    }

    @Test
    fun `zoom stays between a third and four times`() {
        assertEquals(0.3f, DeviceIconCrop.clampZoom(0.01f), 0.0001f)
        assertEquals(4f, DeviceIconCrop.clampZoom(9f), 0.0001f)
        assertEquals(1.7f, DeviceIconCrop.clampZoom(1.7f), 0.0001f)
    }

    @Test
    fun `the safe area guide keeps the artwork off the edge of the icon`() {
        assertEquals(0.84f, DeviceIconCrop.SAFE_AREA, 0.0001f)
    }
}
