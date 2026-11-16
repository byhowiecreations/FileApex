package com.fileapex.ui.notes

import com.fileapex.ui.loadNotesBubbleThumbnail
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test

class NotesBubbleThumbnailTest {
    private fun noisyJpeg(width: Int, height: Int, quality: Float): ByteArray {
        val random = Random(7)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { writer.output = it; writer.write(null, IIOImage(image, null, null), param) }
        writer.dispose()
        return out.toByteArray()
    }

    private fun previewBase64(): String = Base64.getEncoder().encodeToString(noisyJpeg(64, 64, 0.5f))

    private fun bigImage(): File {
        val file = Files.createTempFile("big-note-image", ".jpg").toFile().apply { deleteOnExit() }
        file.writeBytes(noisyJpeg(2600, 2100, 0.95f))
        check(file.length() > 4L * 1024L * 1024L) { "test image must be over the 4 MB cap, is ${file.length()}" }
        return file
    }

    @Test
    fun anImageOverTheFileCapStillGetsItsThumbnailFromTheEmbeddedPreview() = runBlocking {
        val thumb = loadNotesBubbleThumbnail(bigImage().absolutePath, "Screenshot.jpg", previewBase64())
        assertNotNull("a 6 MB local image must show its preview, not a bare file chip", thumb)
    }

    @Test
    fun aMissingLocalFileFallsBackToThePreview() = runBlocking {
        assertNotNull(loadNotesBubbleThumbnail("/nowhere/at/all.jpg", "all.jpg", previewBase64()))
    }

    @Test
    fun aSmallLocalFileWithoutAPreviewStillLoadsFromTheFile() = runBlocking {
        val file = Files.createTempFile("small-note-image", ".jpg").toFile().apply { deleteOnExit() }
        file.writeBytes(noisyJpeg(200, 200, 0.6f))
        assertNotNull(loadNotesBubbleThumbnail(file.absolutePath, "small.jpg", null))
    }

    @Test
    fun anOversizedFileWithoutAPreviewIsSampledForItsThumbnailInsteadOfBeingReadWhole() = runBlocking {
        assertNotNull(loadNotesBubbleThumbnail(bigImage().absolutePath, "Screenshot.jpg", null))
    }
}
