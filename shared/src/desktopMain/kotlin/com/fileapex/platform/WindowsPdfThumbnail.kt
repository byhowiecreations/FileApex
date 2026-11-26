package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.fileapex.util.cancellableCatching
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Semaphore
import kotlin.math.abs
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * First-page PDF thumbnails on Windows via the in-process Windows Shell thumbnail API (`IShellItemImageFactory`)
 * over the existing JNA dependency: zero PowerShell, zero child processes, and zero added libraries.
 */
internal object WindowsPdfThumbnail {
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val S_OK = 0
    private const val S_FALSE = 1
    private const val SIIGBF_THUMBNAILONLY = 0x08
    private const val DIB_RGB_COLORS = 0
    private const val BI_RGB = 0
    private const val MAX_BITMAP_EDGE = 2048
    private val gate = Semaphore(2)

    /** `{bcc18b79-ba16-442f-80c4-8a59c30c463b}` in Windows little-endian GUID byte layout. */
    internal val IID_ISHELL_ITEM_IMAGE_FACTORY = byteArrayOf(
        0x79, 0x8b.toByte(), 0xc1.toByte(), 0xbc.toByte(),
        0x16, 0xba.toByte(),
        0x2f, 0x44,
        0x80.toByte(), 0xc4.toByte(), 0x8a.toByte(), 0x59, 0xc3.toByte(), 0x0c, 0x46, 0x3b
    )

    internal fun packSize(cx: Int, cy: Int): Long =
        (cy.toLong() shl 32) or (cx.toLong() and 0xFFFF_FFFFL)

    internal fun bgraToImageBitmap(width: Int, height: Int, bgra: ByteArray): ImageBitmap? {
        if (width <= 0 || height <= 0 || width > MAX_BITMAP_EDGE || height > MAX_BITMAP_EDGE) return null
        val expected = width * height * 4
        if (bgra.size < expected) return null
        val copy = bgra.copyOf(expected)
        var anyNonZeroAlpha = false
        for (i in 3 until expected step 4) {
            if (copy[i].toInt() != 0) {
                anyNonZeroAlpha = true
                break
            }
        }
        if (!anyNonZeroAlpha) {
            for (i in 3 until expected step 4) {
                copy[i] = 0xFF.toByte()
            }
        }
        val bitmap = Bitmap()
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        if (!bitmap.allocPixels(info)) return null
        bitmap.installPixels(info, copy, width * 4)
        return bitmap.asComposeImageBitmap()
    }

    fun render(absolutePath: String, edge: Int): ImageBitmap? {
        if (absolutePath.isBlank() || edge <= 0) return null
        gate.acquire()
        return try {
            cancellableCatching("WindowsPdfThumbnail render") {
                renderUnsafe(absolutePath, edge)
            }.getOrNull()
        } finally {
            gate.release()
        }
    }

    private fun renderUnsafe(absolutePath: String, edge: Int): ImageBitmap? {
        val coInitHr = Ole32Api.INSTANCE.CoInitializeEx(Pointer.NULL, COINIT_APARTMENTTHREADED)
        val shouldUninit = coInitHr == S_OK || coInitHr == S_FALSE
        try {
            val factoryRef = PointerByReference()
            val itemHr = Shell32Api.INSTANCE.SHCreateItemFromParsingName(
                WString(absolutePath),
                Pointer.NULL,
                IID_ISHELL_ITEM_IMAGE_FACTORY,
                factoryRef
            )
            if (itemHr != S_OK) return null
            val factoryPtr = factoryRef.value ?: return null
            try {
                val vtable = factoryPtr.getPointer(0) ?: return null
                val getImageFn = Function.getFunction(
                    vtable.getPointer(3L * Native.POINTER_SIZE),
                    Function.ALT_CONVENTION
                )
                val hBitmapRef = PointerByReference()
                val imgHr = getImageFn.invokeInt(
                    arrayOf(factoryPtr, packSize(edge, edge), SIIGBF_THUMBNAILONLY, hBitmapRef)
                )
                if (imgHr != S_OK) return null
                val hBitmap = hBitmapRef.value ?: return null
                try {
                    return hBitmapToImageBitmap(hBitmap)?.centeredOnSquare(edge, PageThumbnailBackdrop)
                } finally {
                    Gdi32Api.INSTANCE.DeleteObject(hBitmap)
                }
            } finally {
                val vtable = factoryPtr.getPointer(0)
                if (vtable != null) {
                    val releaseFn = Function.getFunction(
                        vtable.getPointer(2L * Native.POINTER_SIZE),
                        Function.ALT_CONVENTION
                    )
                    releaseFn.invokeInt(arrayOf(factoryPtr))
                }
            }
        } finally {
            if (shouldUninit) Ole32Api.INSTANCE.CoUninitialize()
        }
    }

    private fun hBitmapToImageBitmap(hBitmap: Pointer): ImageBitmap? {
        val hdc = User32Api.INSTANCE.GetDC(Pointer.NULL) ?: return null
        try {
            val bmi = Memory(40).apply {
                clear()
                setInt(0, 40) // BITMAPINFOHEADER.biSize
            }
            if (Gdi32Api.INSTANCE.GetDIBits(hdc, hBitmap, 0, 0, Pointer.NULL, bmi, DIB_RGB_COLORS) == 0) {
                return null
            }
            val width = bmi.getInt(4)
            val height = abs(bmi.getInt(8))
            if (width <= 0 || height <= 0 || width > MAX_BITMAP_EDGE || height > MAX_BITMAP_EDGE) return null
            bmi.setInt(8, -height) // negative height requests top-down BGRA pixels
            bmi.setShort(12, 1.toShort())
            bmi.setShort(14, 32.toShort())
            bmi.setInt(16, BI_RGB)
            bmi.setInt(20, 0)
            val bytes = Memory(width.toLong() * height.toLong() * 4L)
            val scanLines = Gdi32Api.INSTANCE.GetDIBits(hdc, hBitmap, 0, height, bytes, bmi, DIB_RGB_COLORS)
            if (scanLines == 0) return null
            return bgraToImageBitmap(width, height, bytes.getByteArray(0, width * height * 4))
        } finally {
            User32Api.INSTANCE.ReleaseDC(Pointer.NULL, hdc)
        }
    }

    private interface Ole32Api : StdCallLibrary {
        fun CoInitializeEx(pvReserved: Pointer?, dwCoInit: Int): Int
        fun CoUninitialize()

        companion object {
            val INSTANCE: Ole32Api by lazy { Native.load("ole32", Ole32Api::class.java) }
        }
    }

    private interface Shell32Api : StdCallLibrary {
        fun SHCreateItemFromParsingName(
            pszPath: WString,
            pbc: Pointer?,
            riid: ByteArray,
            ppv: PointerByReference
        ): Int

        companion object {
            val INSTANCE: Shell32Api by lazy { Native.load("shell32", Shell32Api::class.java) }
        }
    }

    private interface User32Api : StdCallLibrary {
        fun GetDC(hWnd: Pointer?): Pointer?
        fun ReleaseDC(hWnd: Pointer?, hDC: Pointer): Int

        companion object {
            val INSTANCE: User32Api by lazy { Native.load("user32", User32Api::class.java) }
        }
    }

    private interface Gdi32Api : StdCallLibrary {
        fun GetDIBits(
            hdc: Pointer,
            hbmp: Pointer,
            start: Int,
            cLines: Int,
            lpvBits: Pointer?,
            lpbmi: Pointer,
            usage: Int
        ): Int

        fun DeleteObject(ho: Pointer): Boolean

        companion object {
            val INSTANCE: Gdi32Api by lazy { Native.load("gdi32", Gdi32Api::class.java) }
        }
    }
}
