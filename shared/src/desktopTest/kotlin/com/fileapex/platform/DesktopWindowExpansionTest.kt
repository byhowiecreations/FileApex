package com.fileapex.platform

import com.fileapex.ui.FileManagerPresence
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWindowExpansionTest {
    private val screen = WindowFrame(0f, 0f, 1728f, 1080f)

    @Test
    fun `a narrow window widens to the target and keeps its left edge`() {
        val out = DesktopWindowExpansion.expand(WindowFrame(100f, 80f, 440f, 700f), screen, 1100f)!!
        assertEquals(WindowFrame(100f, 80f, 1100f, 700f), out)
    }

    @Test
    fun `widening near the right edge shifts the window left so it stays on screen`() {
        val out = DesktopWindowExpansion.expand(WindowFrame(1500f, 80f, 440f, 700f), screen, 1100f)!!
        assertEquals(628f, out.x, 0.001f)
        assertEquals(1100f, out.width, 0.001f)
    }

    @Test
    fun `a window already wide enough is left alone`() {
        assertNull(DesktopWindowExpansion.expand(WindowFrame(0f, 0f, 1300f, 700f), screen, 1100f))
    }

    @Test
    fun `the target is clamped to the screen`() {
        val out = DesktopWindowExpansion.expand(WindowFrame(0f, 0f, 440f, 700f), WindowFrame(0f, 0f, 900f, 700f), 1100f)!!
        assertEquals(900f, out.width, 0.001f)
    }

    @Test
    fun `leaving restores the saved frame when the user did not resize while expanded`() {
        val saved = WindowFrame(100f, 80f, 440f, 700f)
        val expanded = WindowFrame(100f, 80f, 1100f, 700f)
        assertEquals(saved, DesktopWindowExpansion.restore(saved, expanded, expanded.copy(x = 140f)))
    }

    @Test
    fun `leaving keeps a size the user set while expanded`() {
        val saved = WindowFrame(100f, 80f, 440f, 700f)
        val expanded = WindowFrame(100f, 80f, 1100f, 700f)
        assertNull(DesktopWindowExpansion.restore(saved, expanded, expanded.copy(width = 1250f)))
    }

    @Test
    fun `usable screen area excludes the dock and the menu bar`() {
        val usable = DesktopWindowExpansion.usable(WindowFrame(0f, 0f, 1728f, 1117f), FrameInsets(top = 38f, left = 0f, bottom = 70f, right = 0f))
        assertEquals(WindowFrame(0f, 38f, 1728f, 1009f), usable)
    }

    @Test
    fun `a widened window is kept above the dock`() {
        val usable = WindowFrame(0f, 38f, 1728f, 1009f)
        val out = DesktopWindowExpansion.expand(WindowFrame(100f, 900f, 440f, 700f), usable, 1100f)!!
        assertEquals(1047f - 700f, out.y, 0.001f)
    }

    @Test
    fun `a window taller than the usable area is shortened to fit`() {
        val usable = WindowFrame(0f, 38f, 1728f, 600f)
        val out = DesktopWindowExpansion.expand(WindowFrame(100f, 100f, 440f, 700f), usable, 1100f)!!
        assertEquals(600f, out.height, 0.001f)
        assertEquals(38f, out.y, 0.001f)
    }

    @Test
    fun `AWT top left y becomes the Cocoa bottom left y of the primary screen`() {
        assertEquals(1117f - 80f - 700f, MacWindowCoordinates.cocoaY(awtY = 80f, height = 700f, primaryScreenHeight = 1117f), 0.001f)
        assertEquals(0f, MacWindowCoordinates.cocoaY(awtY = 417f, height = 700f, primaryScreenHeight = 1117f), 0.001f)
    }

    @Test
    fun `the window easing starts at zero, ends at one and never goes backwards`() {
        assertEquals(0f, WindowEasing.at(0f), 0.0001f)
        assertEquals(1f, WindowEasing.at(1f), 0.0001f)
        var last = 0f
        for (i in 1..100) {
            val v = WindowEasing.at(i / 100f)
            assertTrue("easing went backwards at step $i", v >= last)
            last = v
        }
    }

    @Test
    fun `settled presence reports opening and closing at once but ignores a remount blip`() = kotlinx.coroutines.runBlocking {
        FileManagerPresence.reset()
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<Boolean, Long>>()
        val start = System.nanoTime()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            FileManagerPresence.settled.collect { seen += it to (System.nanoTime() - start) / 1_000_000 }
        }
        kotlinx.coroutines.delay(100)
        FileManagerPresence.enter()
        kotlinx.coroutines.delay(100)
        val enteredAt = seen.last { it.first }.second
        assertTrue("opening was reported late: $seen", enteredAt - 100 < 60)

        val before = seen.size
        FileManagerPresence.leave()
        kotlinx.coroutines.delay(15)
        FileManagerPresence.enter()
        kotlinx.coroutines.delay(200)
        assertTrue("a remount blip must not close the window: $seen", seen.drop(before).none { !it.first })

        val leftAt = (System.nanoTime() - start) / 1_000_000
        FileManagerPresence.leave()
        kotlinx.coroutines.delay(400)
        job.cancel()
        val closed = seen.last { !it.first }.second
        assertTrue("closing waited too long: ${closed - leftAt} ms", closed - leftAt < 120)
    }

    @Test
    fun `presence counts several explorers and reports active only while one is open`() {
        FileManagerPresence.reset()
        assertFalse(FileManagerPresence.isActive)
        FileManagerPresence.enter(); FileManagerPresence.enter()
        assertTrue(FileManagerPresence.isActive)
        FileManagerPresence.leave()
        assertTrue(FileManagerPresence.isActive)
        FileManagerPresence.leave()
        assertFalse(FileManagerPresence.isActive)
        FileManagerPresence.leave()
        assertFalse(FileManagerPresence.isActive)
    }
}
