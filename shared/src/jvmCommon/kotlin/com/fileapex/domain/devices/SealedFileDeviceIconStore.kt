package com.fileapex.domain.devices

import androidx.compose.ui.graphics.ImageBitmap
import com.fileapex.data.seal.SealedText
import com.fileapex.platform.decodeImageBytes
import com.fileapex.platform.encodePng
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * One sealed file per device id in [directory]. The file name is a hash of the id, so an id can never name a path, and the
 * id is bound into the seal. Decoded icons are kept in a small cache so a screen of cards does not read the disk again.
 */
class SealedFileDeviceIconStore(private val directory: File) : DeviceIconStore {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_CACHED
    }
    private val versionFlow = MutableStateFlow(0)
    override val version: StateFlow<Int> = versionFlow.asStateFlow()

    private fun file(deviceId: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "${hash.take(32)}.icon")
    }

    private fun purpose(deviceId: String) = "device_icon:$deviceId"

    override fun get(deviceId: String): ImageBitmap? {
        synchronized(cache) { cache[deviceId] }?.let { return it }
        val text = SealedText.readFile(file(deviceId), purpose(deviceId))
        if (text.isBlank()) return null
        val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        val bitmap = decodeImageBytes(bytes, MAX_EDGE) ?: return null
        synchronized(cache) { cache[deviceId] = bitmap }
        return bitmap
    }

    override suspend fun save(deviceId: String, bitmap: ImageBitmap): Boolean = withContext(Dispatchers.IO) {
        val bytes = encodePng(bitmap) ?: return@withContext false
        val written = SealedText.writeFile(file(deviceId), purpose(deviceId), Base64.getEncoder().encodeToString(bytes))
        if (written) {
            synchronized(cache) { cache[deviceId] = bitmap }
            versionFlow.value += 1
        }
        written
    }

    override fun remove(deviceId: String) {
        file(deviceId).delete()
        synchronized(cache) { cache.remove(deviceId) }
        versionFlow.value += 1
    }

    private companion object {
        const val MAX_CACHED = 32
        const val MAX_EDGE = 512
    }
}
