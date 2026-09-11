package com.fileapex.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object FileApexIcons {
    /**
     * Material 3 Expressive ATR (Automatic Target Recognition) icon.
     * Three solid circular nodes arranged in an equilateral triangle without connecting bars,
     * exactly matching the official ATR layout symbol.
     */
    val Atr: ImageVector by lazy {
        ImageVector.Builder(
            name = "Atr",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            val r = 2.5f
            val kappa = 0.55228475f * r
            path(fill = SolidColor(Color.White)) {
                // Top dot: center (12, 6.2)
                val topCx = 12f
                val topCy = 6.2f
                moveTo(topCx, topCy - r)
                curveTo(topCx + kappa, topCy - r, topCx + r, topCy - kappa, topCx + r, topCy)
                curveTo(topCx + r, topCy + kappa, topCx + kappa, topCy + r, topCx, topCy + r)
                curveTo(topCx - kappa, topCy + r, topCx - r, topCy + kappa, topCx - r, topCy)
                curveTo(topCx - r, topCy - kappa, topCx - kappa, topCy - r, topCx, topCy - r)
                close()

                // Bottom-left dot: center (6.8, 16.2)
                val blCx = 6.8f
                val blCy = 16.2f
                moveTo(blCx, blCy - r)
                curveTo(blCx + kappa, blCy - r, blCx + r, blCy - kappa, blCx + r, blCy)
                curveTo(blCx + r, blCy + kappa, blCx + kappa, blCy + r, blCx, blCy + r)
                curveTo(blCx - kappa, blCy + r, blCx - r, blCy + kappa, blCx - r, blCy)
                curveTo(blCx - r, blCy - kappa, blCx - kappa, blCy - r, blCx, blCy - r)
                close()

                // Bottom-right dot: center (17.2, 16.2)
                val brCx = 17.2f
                val brCy = 16.2f
                moveTo(brCx, brCy - r)
                curveTo(brCx + kappa, brCy - r, brCx + r, brCy - kappa, brCx + r, brCy)
                curveTo(brCx + r, brCy + kappa, brCx + kappa, brCy + r, brCx, brCy + r)
                curveTo(brCx - kappa, brCy + r, brCx - r, brCy + kappa, brCx - r, brCy)
                curveTo(brCx - r, brCy - kappa, brCx - kappa, brCy - r, brCx, brCy - r)
                close()
            }
        }.build()
    }

    /**
     * "Right Panel Close": Outer rounded window with vertical sidebar divider and right-pointing
     * triangle (▶), indicating clicking will close the right detail pane (collapse to compact).
     */
    val RightPanelClose: ImageVector by lazy {
        ImageVector.Builder(
            name = "RightPanelClose",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Window frame with vertical panel divider
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.75f
            ) {
                moveTo(6f, 4f)
                lineTo(18f, 4f)
                curveTo(19.65f, 4f, 21f, 5.35f, 21f, 7f)
                lineTo(21f, 17f)
                curveTo(21f, 18.65f, 19.65f, 20f, 18f, 20f)
                lineTo(6f, 20f)
                curveTo(4.35f, 20f, 3f, 18.65f, 3f, 17f)
                lineTo(3f, 7f)
                curveTo(3f, 5.35f, 4.35f, 4f, 6f, 4f)
                close()
                // Divider separating the right panel
                moveTo(16f, 4f)
                lineTo(16f, 20f)
            }
            // Right-facing triangle in main pane
            path(fill = SolidColor(Color.White)) {
                moveTo(8.5f, 8.5f)
                lineTo(8.5f, 15.5f)
                lineTo(12.5f, 12f)
                close()
            }
        }.build()
    }

    /**
     * "Right Panel Open": Outer rounded window with vertical sidebar divider and left-pointing
     * triangle (◀), indicating clicking will open the right detail pane (expand to dual-pane).
     */
    val RightPanelOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "RightPanelOpen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Window frame with vertical panel divider
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.75f
            ) {
                moveTo(6f, 4f)
                lineTo(18f, 4f)
                curveTo(19.65f, 4f, 21f, 5.35f, 21f, 7f)
                lineTo(21f, 17f)
                curveTo(21f, 18.65f, 19.65f, 20f, 18f, 20f)
                lineTo(6f, 20f)
                curveTo(4.35f, 20f, 3f, 18.65f, 3f, 17f)
                lineTo(3f, 7f)
                curveTo(3f, 5.35f, 4.35f, 4f, 6f, 4f)
                close()
                // Divider separating the right panel
                moveTo(16f, 4f)
                lineTo(16f, 20f)
            }
            // Left-facing triangle in main pane
            path(fill = SolidColor(Color.White)) {
                moveTo(12.5f, 8.5f)
                lineTo(12.5f, 15.5f)
                lineTo(8.5f, 12f)
                close()
            }
        }.build()
    }

    val DesktopExpanded: ImageVector get() = RightPanelClose
    val DesktopCompact: ImageVector get() = RightPanelOpen
}
