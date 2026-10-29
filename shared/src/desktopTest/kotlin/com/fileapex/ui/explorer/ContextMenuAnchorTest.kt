package com.fileapex.ui.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A context menu opens where the finger or the pointer pressed, not at the item's left edge. The popup is placed at the
 * item's bottom-left corner plus an offset, so the offset is the press point minus the item's height.
 */
class ContextMenuAnchorTest {
    private fun anchor(width: Int = 400, height: Int = 60) = ContextMenuAnchor().also { it.onSize(IntSize(width, height)) }

    @Test
    fun theMenuOpensAtThePressPoint() {
        val anchor = anchor().also { it.onPress(Offset(120f, 30f)) }
        assertEquals(Offset(120f, -30f), anchor.menuOffsetPx())
    }

    @Test
    fun withoutAPressTheMenuKeepsItsDefaultPlace() {
        assertEquals(Offset.Zero, anchor().menuOffsetPx())
    }

    @Test
    fun aPressOutsideTheItemIsPulledBackInside() {
        val anchor = anchor().also { it.onPress(Offset(-20f, 500f)) }
        assertEquals(Offset(0f, 0f), anchor.menuOffsetPx())
    }

    @Test
    fun theLatestPressWins() {
        val anchor = anchor()
        anchor.onPress(Offset(10f, 10f))
        anchor.onPress(Offset(390f, 50f))
        assertEquals(Offset(390f, -10f), anchor.menuOffsetPx())
    }
}
