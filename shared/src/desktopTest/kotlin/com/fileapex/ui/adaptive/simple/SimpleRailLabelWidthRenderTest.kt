package com.fileapex.ui.adaptive.simple

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.ui.adaptive.SimpleDestination
import com.fileapex.ui.theme.FileApexTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleRailLabelWidthRenderTest {
    @Test
    fun noRailLabelRunsToTheEdgesOfTheRail() {
        val widthPx = 200
        val scene = ImageComposeScene(width = widthPx, height = 900, density = Density(1f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                SimpleRail(
                    destination = SimpleDestination.Browse,
                    popover = null,
                    bulletinUnreadCount = 0,
                    onSelect = {},
                    onOpenNotes = {},
                    onTogglePopover = {},
                    onMenuAnchor = {},
                    onHiddenChanged = {}
                )
            }
        }
        try {
            val image = scene.render(0L)
            File("build/tmp/simple-rail.png").also { it.parentFile.mkdirs() }.writeBytes(image.encodeToData()!!.bytes)
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
            fun maxChannel(x: Int, y: Int): Int {
                val c = bitmap.getColor(x, y)
                return maxOf(org.jetbrains.skia.Color.getR(c), org.jetbrains.skia.Color.getG(c), org.jetbrains.skia.Color.getB(c))
            }
            // The rail capsule is the one solid mid-gray area at the top of the test image; its text is lighter.
            val capsuleRow = 150
            val capsuleXs = (0 until widthPx).filter { maxChannel(it, capsuleRow) in 60..135 }
            val left = capsuleXs.min()
            val right = capsuleXs.max()
            var textMin = right
            var textMax = left
            for (y in 20..280) for (x in (left + 3)..(right - 3)) {
                if (maxChannel(x, y) > 150) { textMin = minOf(textMin, x); textMax = maxOf(textMax, x) }
            }
            val gapLeft = textMin - left
            val gapRight = right - textMax
            assertTrue("text sits ${gapLeft}px and ${gapRight}px from the capsule edges; it needs at least 6px", gapLeft >= 6 && gapRight >= 6)
        } finally {
            scene.close()
        }
    }
}
