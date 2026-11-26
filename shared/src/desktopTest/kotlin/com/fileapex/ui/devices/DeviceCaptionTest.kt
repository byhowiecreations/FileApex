package com.fileapex.ui.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fileapex.domain.presence.RouteKind
import com.fileapex.presentation.DeviceListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A card prints text under the name only when there is something to say; the route is a badge on the icon. */
class DeviceCaptionTest {
    private fun row(online: Boolean = true, behind: Boolean = false) = DeviceListRow(
        deviceId = "d",
        deviceName = "Device",
        online = online,
        appVersion = "0.21.9",
        reachedBy = RouteKind.Lan,
        isBehind = behind
    )

    private fun captionHeight(row: DeviceListRow): Int {
        var height = -1
        val scene = ImageComposeScene(width = 300, height = 200, density = Density(1f)) {
            Box(modifier = Modifier.onGloballyPositioned { height = it.size.height }) {
                DeviceCaption(row = row, style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        scene.render()
        scene.close()
        return height
    }

    @Test
    fun `an up to date online device has no caption at all`() {
        assertEquals(0, captionHeight(row()))
    }

    @Test
    fun `a device that is behind shows its version`() {
        assertTrue(captionHeight(row(behind = true)) > 0)
    }

    @Test
    fun `an offline device shows its state`() {
        assertTrue(captionHeight(row(online = false)) > 0)
    }

    @Test
    fun `the badge sits fully inside the orb on its 45 degree diagonal`() {
        val orb = 64f
        val badge = 14f
        val inset = orbBadgeInset(orb.dp, badge.dp).value
        val centre = orb - inset - badge / 2f
        val fromOrbCentre = Math.hypot((centre - orb / 2f).toDouble(), (centre - orb / 2f).toDouble())
        assertEquals((orb / 2f - badge / 2f).toDouble(), fromOrbCentre, 0.01)
    }
}
