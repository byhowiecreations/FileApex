package com.fileapex.domain.devices

import androidx.compose.ui.graphics.ImageBitmap
import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.seal.SealedText
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Custom device icons are kept sealed on disk, one file per device id, and read back as the same image. */
class SealedFileDeviceIconStoreTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory("device-icons").toFile()
        SealedText.sealerProvider = { FieldSealer(ByteArray(32) { it.toByte() }) }
    }

    @After
    fun tearDown() {
        SealedText.sealerProvider = { null }
        dir.deleteRecursively()
    }

    private fun bitmap() = ImageBitmap(16, 16)

    @Test
    fun `a saved icon is read back with its size`() = runBlocking {
        val store = SealedFileDeviceIconStore(dir)
        assertTrue(store.save("fx-device-1", bitmap()))
        val loaded = store.get("fx-device-1")
        assertNotNull(loaded)
        assertEquals(16, loaded!!.width)
        assertNull(store.get("fx-other"))
    }

    @Test
    fun `nothing readable is left on disk and a reopened store finds the icon`() = runBlocking {
        SealedFileDeviceIconStore(dir).save("fx-device-1", bitmap())
        val text = dir.listFiles()!!.single().readText()
        assertFalse("looks like a clear PNG", text.contains("iVBOR") || text.startsWith("\u0089PNG"))
        assertNotNull(SealedFileDeviceIconStore(dir).get("fx-device-1"))
    }

    @Test
    fun `an icon is kept by device id and never by a name that could escape the folder`() = runBlocking {
        val store = SealedFileDeviceIconStore(dir)
        assertTrue(store.save("../../escape", bitmap()))
        assertTrue(dir.walkTopDown().filter { it.isFile }.all { it.parentFile == dir })
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun `removing deletes the file and the icon is gone`() = runBlocking {
        val store = SealedFileDeviceIconStore(dir)
        store.save("fx-device-1", bitmap())
        store.remove("fx-device-1")
        assertNull(store.get("fx-device-1"))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `without a key nothing is written`() = runBlocking {
        SealedText.sealerProvider = { null }
        val store = SealedFileDeviceIconStore(dir)
        assertFalse(store.save("fx-device-1", bitmap()))
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `saving bumps the version so cards redraw`() = runBlocking {
        val store = SealedFileDeviceIconStore(dir)
        val before = store.version.value
        store.save("fx-device-1", bitmap())
        assertTrue(store.version.value > before)
    }
}
