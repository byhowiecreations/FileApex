package com.fileapex.ui.explorer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.fileapex.di.FileApexServices
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.preview.RemoteThumbnailPolicy
import com.fileapex.platform.decodeImageBytes
import com.fileapex.presentation.BrowseTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** LRU memory cache for bounded remote Explorer media thumbnails. */
class RemoteThumbnailCache(private val maxEntries: Int = 128) {
    private val map = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)

    @Synchronized
    fun get(key: String): ImageBitmap? = map[key]

    @Synchronized
    fun put(key: String, bitmap: ImageBitmap) {
        map[key] = bitmap
        if (map.size > maxEntries) {
            val eldestKey = map.entries.firstOrNull()?.key ?: return
            map.remove(eldestKey)
        }
    }
}

internal val LocalRemoteThumbnailFetcher = staticCompositionLocalOf<(suspend (RemoteFileItem) -> ImageBitmap?)?> {
    null
}

@Composable
internal fun rememberRemoteThumbnailFetcher(target: BrowseTarget): (suspend (RemoteFileItem) -> ImageBitmap?)? {
    val remote = target as? BrowseTarget.Remote ?: return null
    val cache = remember(remote.deviceId) { RemoteThumbnailCache() }
    return remember(remote.deviceId, remote.host, remote.port) {
        val fetcher: suspend (RemoteFileItem) -> ImageBitmap? = fetcher@{ item ->
            if (!RemoteThumbnailPolicy.isMediaCandidate(item)) {
                return@fetcher null
            }
            val cacheKey = "${remote.deviceId}|${item.absolutePath}|${item.lastModified}|${item.sizeBytes}"
            cache.get(cacheKey)?.let { return@fetcher it }
            val bytes = withContext(Dispatchers.IO) {
                FileApexServices.client.downloads.fetchRemoteThumbnailBytes(
                    host = remote.host,
                    port = remote.port,
                    remotePath = item.absolutePath,
                    maxEdge = RemoteThumbnailPolicy.DEFAULT_EDGE
                )
            } ?: return@fetcher null
            val decoded = withContext(Dispatchers.Default) {
                decodeImageBytes(bytes, maxEdge = RemoteThumbnailPolicy.DEFAULT_EDGE)
            }
            if (decoded != null) {
                cache.put(cacheKey, decoded)
            }
            decoded
        }
        fetcher
    }
}
