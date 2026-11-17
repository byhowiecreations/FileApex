package com.fileapex.ui.motion

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileApexMotionTest {
    private fun millis(spec: Any): Int = (spec as TweenSpec<*>).durationMillis

    @Test
    fun `every user triggered motion is at most 300 ms and an exit is faster than its enter`() {
        for (kind in listOf(MotionKind.Fade, MotionKind.Move, MotionKind.Scale)) {
            val enter = millis(FileApexMotion.enter<Float>(kind, reduced = false))
            val exit = millis(FileApexMotion.exit<Float>(kind, reduced = false))
            assertTrue("$kind enter is $enter ms", enter in 1..FileApexMotion.MAX_UI_MILLIS)
            assertTrue("$kind exit is $exit ms", exit in 1..FileApexMotion.MAX_UI_MILLIS)
            assertTrue("$kind exit ($exit) must be shorter than enter ($enter)", exit < enter)
        }
        assertEquals(FileApexMotion.PRESS_MILLIS, millis(FileApexMotion.press<Float>(reduced = false)))
    }

    @Test
    fun `with reduced motion movement and scale are instant and fades stay short`() {
        assertTrue(FileApexMotion.enter<Float>(MotionKind.Move, reduced = true) is SnapSpec)
        assertTrue(FileApexMotion.exit<Float>(MotionKind.Move, reduced = true) is SnapSpec)
        assertTrue(FileApexMotion.enter<Float>(MotionKind.Scale, reduced = true) is SnapSpec)
        assertTrue(FileApexMotion.exit<Float>(MotionKind.Scale, reduced = true) is SnapSpec)
        assertTrue(FileApexMotion.press<Float>(reduced = true) is SnapSpec)
        assertTrue(FileApexMotion.drag<Float>(reduced = true) is SnapSpec)
        assertTrue(millis(FileApexMotion.enter<Float>(MotionKind.Fade, reduced = true)) <= FileApexMotion.REDUCED_FADE_MILLIS)
        assertTrue(millis(FileApexMotion.exit<Float>(MotionKind.Fade, reduced = true)) <= FileApexMotion.REDUCED_FADE_MILLIS)
    }

    @Test
    fun `a drag uses a spring and a decorative animation keeps its own length but stops when reduced`() {
        assertTrue(FileApexMotion.drag<Float>(reduced = false) is SpringSpec)
        assertEquals(900, millis(FileApexMotion.decorative<Float>(900, reduced = false)))
        assertTrue(FileApexMotion.decorative<Float>(900, reduced = true) is SnapSpec)
    }
}
