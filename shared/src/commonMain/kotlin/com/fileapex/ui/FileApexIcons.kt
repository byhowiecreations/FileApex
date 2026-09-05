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
}
