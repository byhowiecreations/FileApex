package com.fileapex.platform

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import com.fileapex.domain.preview.ThumbnailKind
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Document thumbnails are square so every row crops them the same way, and macOS draws a PDF's first page with its own tool. */
class ThumbnailLayoutTest {
    private fun pixel(bitmap: ImageBitmap, x: Int, y: Int): Int {
        val buffer = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(buffer)
        return buffer[y * bitmap.width + x]
    }

    @Test
    fun `a tall picture is centered on a square with a backdrop`() {
        val tall = ImageBitmap(50, 100)
        Canvas(tall).drawRect(0f, 0f, 50f, 100f, Paint().apply { color = Color.Red })
        val square = tall.centeredOnSquare(64, Color(0xFFE8EAED))
        assertEquals(64, square.width)
        assertEquals(64, square.height)
        assertEquals(Color(0xFFE8EAED).toArgb(), pixel(square, 2, 2))
        assertEquals(Color.Red.toArgb(), pixel(square, 32, 32))
    }

    @Test
    fun `on macOS a pdf gets its first page as a square thumbnail`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac"))
        val dir = createTempDirectory("pdf-thumb").toFile()
        try {
            val pdf = File(dir, "one.pdf")
            pdf.writeText(
                "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
                    "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
                    "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] >>\nendobj\n" +
                    "trailer\n<< /Size 4 /Root 1 0 R >>\n%%EOF\n"
            )
            val thumbnail = decodeDocumentThumbnail(pdf.absolutePath, ThumbnailKind.Pdf, 128)
            assertNotNull(thumbnail)
            assertEquals(128, thumbnail!!.width)
            assertEquals(128, thumbnail.height)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `linux pdf thumbnails follow the freedesktop uri md5 cache layout`() {
        val uri = LinuxPdfThumbnail.canonicalFileUri("/home/cliff/My Report #1.pdf")
        assertEquals("file:///home/cliff/My%20Report%20%231.pdf", uri)
        val md5 = LinuxPdfThumbnail.md5Hex(uri)
        assertEquals(32, md5.length)

        val cacheDir = createTempDirectory("linux-thumbs").toFile()
        try {
            val path = "/home/cliff/My Report #1.pdf"
            assertNull(LinuxPdfThumbnail.render(path, 128, cacheDir))

            val normalDir = File(cacheDir, "normal").apply { mkdirs() }
            val img = java.awt.image.BufferedImage(40, 80, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
                createGraphics().apply {
                    color = java.awt.Color.BLUE
                    fillRect(0, 0, 40, 80)
                    dispose()
                }
            }
            val normalPng = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
            File(normalDir, "$md5.png").writeBytes(normalPng)

            val fromNormal = LinuxPdfThumbnail.render(path, 128, cacheDir)
            assertNotNull(fromNormal)
            assertEquals(128, fromNormal!!.width)
            assertEquals(128, fromNormal.height)
            assertEquals(Color(0xFFE8EAED).toArgb(), pixel(fromNormal, 2, 2))
            assertEquals(Color.Blue.toArgb(), pixel(fromNormal, 64, 64))

            val largeDir = File(cacheDir, "large").apply { mkdirs() }
            val redImg = java.awt.image.BufferedImage(80, 40, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
                createGraphics().apply {
                    color = java.awt.Color.RED
                    fillRect(0, 0, 80, 40)
                    dispose()
                }
            }
            val largePng = ByteArrayOutputStream().also { ImageIO.write(redImg, "png", it) }.toByteArray()
            File(largeDir, "$md5.png").writeBytes(largePng)

            val fromLarge = LinuxPdfThumbnail.render(path, 128, cacheDir)
            assertNotNull(fromLarge)
            assertEquals(Color.Red.toArgb(), pixel(fromLarge!!, 64, 64))
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `windows shell image factory guid packing and bgra conversion`() {
        assertEquals((96L shl 32) or 128L, WindowsPdfThumbnail.packSize(128, 96))
        val expectedGuid = byteArrayOf(
            0x79, 0x8b.toByte(), 0xc1.toByte(), 0xbc.toByte(),
            0x16, 0xba.toByte(),
            0x2f, 0x44,
            0x80.toByte(), 0xc4.toByte(), 0x8a.toByte(), 0x59, 0xc3.toByte(), 0x0c, 0x46, 0x3b
        )
        assertArrayEquals(expectedGuid, WindowsPdfThumbnail.IID_ISHELL_ITEM_IMAGE_FACTORY)

        // 2x2 BGRA with zero alpha (typical GDI DIB) -> coerced opaque
        val bgraZeroAlpha = byteArrayOf(
            0x00, 0x00, 0xFF.toByte(), 0x00, // Red
            0x00, 0xFF.toByte(), 0x00, 0x00, // Green
            0xFF.toByte(), 0x00, 0x00, 0x00, // Blue
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00 // White
        )
        val bitmap = WindowsPdfThumbnail.bgraToImageBitmap(2, 2, bgraZeroAlpha)
        assertNotNull(bitmap)
        assertEquals(2, bitmap!!.width)
        assertEquals(2, bitmap.height)
        assertEquals(Color.Red.toArgb(), pixel(bitmap, 0, 0))
        assertEquals(Color.Green.toArgb(), pixel(bitmap, 1, 0))
        assertEquals(Color.Blue.toArgb(), pixel(bitmap, 0, 1))
        assertEquals(Color.White.toArgb(), pixel(bitmap, 1, 1))
    }
}
