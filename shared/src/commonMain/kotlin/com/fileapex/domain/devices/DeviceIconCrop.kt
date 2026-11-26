package com.fileapex.domain.devices

import androidx.compose.ui.geometry.Offset
import kotlin.math.min

/**
 * Placing a picked picture inside the round device icon. The icon is a unit square. At zoom 1 the picture covers it (its
 * shorter side fits), a smaller zoom shrinks it so it can sit inside the safe ring, a larger zoom crops into it.
 * [offset] is how far the user moved the picture, in icon widths.
 */
object DeviceIconCrop {
    const val MIN_ZOOM = 0.3f
    const val MAX_ZOOM = 4f

    /** The share of the icon the artwork should stay inside so it never touches the edge; drawn as a guide. */
    const val SAFE_AREA = 0.84f

    /** Where the whole picture lands in the unit square: a rectangle that may extend past the icon when zoomed in. */
    data class Placement(val left: Float, val top: Float, val width: Float, val height: Float)

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    private fun limit(extent: Float): Float = if (extent >= 1f) (extent - 1f) / 2f else (1f - extent) / 2f

    /**
     * A picture larger than the icon keeps covering it, and a smaller one stays fully inside it, so the user can place
     * artwork anywhere in the icon but never drag it out of sight or leave the icon showing a gap behind a big picture.
     */
    fun clampOffset(offset: Offset, sourceWidth: Int, sourceHeight: Int, zoom: Float): Offset {
        val shorter = min(sourceWidth, sourceHeight).toFloat()
        val maxX = limit(sourceWidth / shorter * zoom)
        val maxY = limit(sourceHeight / shorter * zoom)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    fun placement(sourceWidth: Int, sourceHeight: Int, zoom: Float, offset: Offset): Placement {
        val z = clampZoom(zoom)
        val o = clampOffset(offset, sourceWidth, sourceHeight, z)
        val shorter = min(sourceWidth, sourceHeight).toFloat()
        val width = sourceWidth / shorter * z
        val height = sourceHeight / shorter * z
        return Placement(left = 0.5f - width / 2f + o.x, top = 0.5f - height / 2f + o.y, width = width, height = height)
    }
}
