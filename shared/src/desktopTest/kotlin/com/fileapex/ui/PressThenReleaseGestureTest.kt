package com.fileapex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Android file row opens its menu when the finger is lifted after a long hold. A real finger rolls a little while
 * it holds, and a perfectly still one sends no move events at all, so neither may stop the menu from opening.
 */
class PressThenReleaseGestureTest {
    private val holdMs = 800L

    private class Taps { var clicks = 0; var menus = 0 }

    private fun run(steps: (send: (PointerEventType, Offset) -> Unit, hold: () -> Unit) -> Unit): Taps {
        val taps = Taps()
        val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
            Box(
                Modifier.fillMaxSize().androidPressThenRelease(
                    onClick = { taps.clicks++ },
                    onLongPressRelease = { taps.menus++ }
                )
            )
        }
        scene.render()
        var time = 0L
        val send: (PointerEventType, Offset) -> Unit = { type, at ->
            scene.sendPointerEvent(type, at, timeMillis = time, type = PointerType.Touch)
            scene.render()
            Unit
        }
        val hold: () -> Unit = {
            Thread.sleep(holdMs)
            time += holdMs
            scene.render()
            Unit
        }
        steps(send, hold)
        scene.close()
        return taps
    }

    private val at = Offset(200f, 200f)

    @Test
    fun aMotionlessHoldThenReleaseOpensTheMenu() {
        val taps = run { send, hold ->
            send(PointerEventType.Press, at)
            hold()
            send(PointerEventType.Release, at)
        }
        assertEquals(1, taps.menus)
        assertEquals(0, taps.clicks)
    }

    @Test
    fun aFingerThatRollsWhileHoldingStillOpensTheMenu() {
        val taps = run { send, hold ->
            send(PointerEventType.Press, at)
            hold()
            send(PointerEventType.Move, at + Offset(0f, 40f))
            send(PointerEventType.Release, at + Offset(0f, 40f))
        }
        assertEquals(1, taps.menus)
    }

    @Test
    fun aRealDragAfterTheHoldDoesNotOpenTheMenu() {
        val taps = run { send, hold ->
            send(PointerEventType.Press, at)
            hold()
            send(PointerEventType.Move, at + Offset(0f, 300f))
            send(PointerEventType.Release, at + Offset(0f, 300f))
        }
        assertEquals(0, taps.menus)
    }

    @Test
    fun movingBeforeTheHoldIsOverIsAScrollNotAMenu() {
        val taps = run { send, hold ->
            send(PointerEventType.Press, at)
            send(PointerEventType.Move, at + Offset(0f, 120f))
            hold()
            send(PointerEventType.Release, at + Offset(0f, 120f))
        }
        assertEquals(0, taps.menus)
    }

    @Test
    fun aQuickTapIsAClickAndNoMenu() {
        val taps = run { send, _ ->
            send(PointerEventType.Press, at)
            send(PointerEventType.Release, at)
        }
        assertEquals(1, taps.clicks)
        assertEquals(0, taps.menus)
    }
}
