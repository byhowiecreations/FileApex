package com.fileapex.ui.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.fileapex.domain.model.RemoteFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the file viewer: moving between files, keeping a zoomed image on screen, and numbering text lines. */
class FileViewerLogicTest {
    private fun file(name: String, dir: Boolean = false) = RemoteFileItem(
        name = name, absolutePath = "/$name", sizeBytes = 1L, lastModified = 0L, isDirectory = dir, mimeType = ""
    )

    @Test
    fun `previous and next skip folders and stop at the ends`() {
        val list = listOf(file("a.txt"), file("dir", dir = true), file("b.txt"), file("c.txt"))
        val middle = FileViewerLogic.neighbours(list, list[2])
        assertEquals("a.txt", middle.previous?.name)
        assertEquals("c.txt", middle.next?.name)
        val first = FileViewerLogic.neighbours(list, list[0])
        assertNull(first.previous)
        assertEquals("b.txt", first.next?.name)
        assertNull(FileViewerLogic.neighbours(list, list[3]).next)
    }

    @Test
    fun `a file that is not in the list has no neighbours`() {
        val n = FileViewerLogic.neighbours(listOf(file("a")), file("zzz"))
        assertNull(n.previous)
        assertNull(n.next)
    }

    @Test
    fun `position counts files only`() {
        val list = listOf(file("dir", dir = true), file("a"), file("b"))
        assertEquals(2 to 2, FileViewerLogic.position(list, list[2]))
        assertNull(FileViewerLogic.position(list, file("zzz")))
    }

    @Test
    fun `zoom stays between one and eight times`() {
        assertEquals(1f, FileViewerLogic.clampScale(0.3f), 0.0001f)
        assertEquals(8f, FileViewerLogic.clampScale(20f), 0.0001f)
        assertEquals(2.5f, FileViewerLogic.clampScale(2.5f), 0.0001f)
    }

    @Test
    fun `an image at normal size cannot be panned`() {
        assertEquals(Offset.Zero, FileViewerLogic.clampOffset(Offset(300f, -200f), scale = 1f, viewport = Size(1000f, 800f)))
    }

    @Test
    fun `a zoomed image can be panned only by the extra size`() {
        val clamped = FileViewerLogic.clampOffset(Offset(900f, -900f), scale = 2f, viewport = Size(1000f, 800f))
        assertEquals(500f, clamped.x, 0.001f)
        assertEquals(-400f, clamped.y, 0.001f)
    }

    @Test
    fun `text lines are split on any line ending and a final newline adds no empty line`() {
        assertEquals(listOf("one", "two", "three"), FileViewerLogic.lines("one\r\ntwo\nthree\n"))
        assertEquals(listOf(""), FileViewerLogic.lines(""))
        assertEquals(listOf("a", "", "b"), FileViewerLogic.lines("a\n\nb"))
    }

    @Test
    fun `the line number gutter is as wide as the largest number`() {
        assertEquals(1, FileViewerLogic.gutterDigits(9))
        assertEquals(2, FileViewerLogic.gutterDigits(10))
        assertEquals(4, FileViewerLogic.gutterDigits(1234))
    }

    @Test
    fun `a long enough sideways drag changes the file, a short one does not`() {
        assertEquals(FileViewerLogic.Swipe.Next, FileViewerLogic.swipe(totalDragPx = -200f, thresholdPx = 120f))
        assertEquals(FileViewerLogic.Swipe.Previous, FileViewerLogic.swipe(totalDragPx = 200f, thresholdPx = 120f))
        assertNull(FileViewerLogic.swipe(totalDragPx = 80f, thresholdPx = 120f))
        assertNull(FileViewerLogic.swipe(totalDragPx = -119f, thresholdPx = 120f))
    }

    @Test
    fun `image swipe is allowed only near normal zoom`() {
        assertTrue(FileViewerLogic.canSwipeImage(1f))
        assertTrue(FileViewerLogic.canSwipeImage(1.01f))
        assertFalse(FileViewerLogic.canSwipeImage(1.2f))
        assertFalse(FileViewerLogic.canSwipeImage(2.5f))
    }
}
