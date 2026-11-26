package com.fileapex.domain.preview

import com.fileapex.domain.model.RemoteFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which files get a thumbnail and of what kind, with size limits so a huge file never stalls a folder. */
class ThumbnailKindTest {
    private fun item(name: String, mime: String = "", size: Long = 1_000L, dir: Boolean = false) = RemoteFileItem(
        name = name, absolutePath = "/x/$name", sizeBytes = size, lastModified = 0L, isDirectory = dir, mimeType = mime
    )

    @Test
    fun `images and videos keep their kinds`() {
        assertEquals(ThumbnailKind.Image, RemoteThumbnailPolicy.kindOf(item("a.JPG")))
        assertEquals(ThumbnailKind.Image, RemoteThumbnailPolicy.kindOf(item("a.bin", "image/png")))
        assertEquals(ThumbnailKind.Video, RemoteThumbnailPolicy.kindOf(item("a.mp4")))
    }

    @Test
    fun `pdf, audio and apk get document kinds`() {
        assertEquals(ThumbnailKind.Pdf, RemoteThumbnailPolicy.kindOf(item("report.pdf")))
        assertEquals(ThumbnailKind.Pdf, RemoteThumbnailPolicy.kindOf(item("report", "application/pdf")))
        for (name in listOf("a.mp3", "a.m4a", "a.flac", "a.ogg", "a.opus", "a.wav", "a.aac")) {
            assertEquals(name, ThumbnailKind.AudioArt, RemoteThumbnailPolicy.kindOf(item(name)))
        }
        assertEquals(ThumbnailKind.AudioArt, RemoteThumbnailPolicy.kindOf(item("track", "audio/mpeg")))
        assertEquals(ThumbnailKind.ApkIcon, RemoteThumbnailPolicy.kindOf(item("app.apk")))
    }

    @Test
    fun `everything else gets none`() {
        assertNull(RemoteThumbnailPolicy.kindOf(item("notes.txt", "text/plain")))
        assertNull(RemoteThumbnailPolicy.kindOf(item("archive.zip")))
        assertNull(RemoteThumbnailPolicy.kindOf(item("folder", dir = true)))
        assertNull(RemoteThumbnailPolicy.kindOf(item("blank").copy(absolutePath = "")))
    }

    @Test
    fun `large files are skipped`() {
        assertEquals(ThumbnailKind.Pdf, RemoteThumbnailPolicy.kindOf(item("a.pdf", size = 100L * 1024 * 1024)))
        assertNull(RemoteThumbnailPolicy.kindOf(item("a.pdf", size = 100L * 1024 * 1024 + 1)))
        assertNull(RemoteThumbnailPolicy.kindOf(item("a.apk", size = 600L * 1024 * 1024)))
    }

    @Test
    fun `the old media check still means image or video only`() {
        assertTrue(RemoteThumbnailPolicy.isMediaCandidate(item("a.jpg")))
        assertTrue(RemoteThumbnailPolicy.isMediaCandidate(item("a.mp4")))
        assertFalse(RemoteThumbnailPolicy.isMediaCandidate(item("a.pdf")))
    }

    @Test
    fun `mime inference names the new kinds`() {
        assertEquals("application/pdf", RemoteThumbnailPolicy.inferMimeType("a.pdf", ""))
        assertEquals("audio/mp3", RemoteThumbnailPolicy.inferMimeType("a.mp3", ""))
        assertEquals("application/vnd.android.package-archive", RemoteThumbnailPolicy.inferMimeType("a.apk", ""))
    }
}
