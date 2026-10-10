package com.fileapex.domain.browse

import com.fileapex.data.clipboard.TransferClipboard
import com.fileapex.data.transfer.FileTransferService
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.PinSessionRequiredException
import com.fileapex.session.DeviceSessionManager
import com.fileapex.util.PathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class BrowserCoordinator(
    private val target: BrowseTarget,
    private val transfer: FileTransferService
) {
    val browseRoot: String = PathUtils.normalize(target.rootPath)
    val isRemote: Boolean = target is BrowseTarget.Remote
    private val remotePinRequired: Boolean =
        (target as? BrowseTarget.Remote)?.pinRequired == true

    fun ensureBrowseAccess() {
        if (!isRemote || !remotePinRequired) return
        if (DeviceSessionManager.isSessionValid(target.deviceId)) return
        throw PinSessionRequiredException()
    }

    private data class CachedBrowse(val timestamp: Long, val listing: BrowseListing)
    private val browseCache = LinkedHashMap<String, CachedBrowse>(16, 0.75f, true)
    private val cacheLock = Any()

    suspend fun listAt(path: String, forceRefresh: Boolean = false): BrowseListing {
        ensureBrowseAccess()
        val normalized = normalizePath(path)
        val now = com.fileapex.util.TimeUtils.now()
        if (!forceRefresh) {
            val cached = synchronized(cacheLock) { browseCache[normalized] }
            if (cached != null && (now - cached.timestamp) < BROWSE_CACHE_TTL_MS) {
                if (isRemote && (now - cached.timestamp) > REVALIDATE_AFTER_MS) revalidateInBackground(path, normalized)
                return cached.listing
            }
        }
        return fetchAndCache(path, normalized, now, forceRefresh)
    }

    /** Emits a normalized path when a background refresh found that folder changed since it was shown. */
    private val _revalidated = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val revalidated: SharedFlow<String> = _revalidated.asSharedFlow()

    private val revalidateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val revalidating = HashSet<String>()

    private fun revalidateInBackground(path: String, normalized: String) {
        synchronized(cacheLock) { if (!revalidating.add(normalized)) return }
        revalidateScope.launch {
            try {
                val before = synchronized(cacheLock) { browseCache[normalized]?.listing }
                val fresh = fetchAndCache(path, normalized, com.fileapex.util.TimeUtils.now())
                if (before != fresh) _revalidated.tryEmit(normalized)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Coroutine entry point: the folder on screen came from cache, so a failed refresh leaves it as it was.
                println("BrowserCoordinator: background refresh failed - ${error::class.simpleName}")
            } finally {
                synchronized(cacheLock) { revalidating.remove(normalized) }
            }
        }
    }

    fun close() {
        revalidateScope.cancel()
    }

    private suspend fun fetchAndCache(path: String, normalized: String, now: Long, forceRefresh: Boolean = false): BrowseListing {
        val result = when (val browseTarget = target) {
            is BrowseTarget.Local -> {
                val listing = transfer.listLocal(path, bypassCache = forceRefresh)
                BrowseListing(
                    directories = listing.directories,
                    files = listing.files
                )
            }
            is BrowseTarget.Remote -> {
                DeviceSessionManager.markDeviceAccessed(browseTarget.deviceId)
                val items = transfer.listRemote(browseTarget.host, browseTarget.port, path)
                BrowseListing(
                    directories = items.filter { it.isDirectory }.sortedBy { it.name.lowercase() },
                    files = items.filter { !it.isDirectory }.sortedBy { it.name.lowercase() }
                )
            }
            is BrowseTarget.Demo -> {
                BrowseListing(
                    directories = com.fileapex.domain.demo.DemoModeState.getDemoDirectories(path),
                    files = com.fileapex.domain.demo.DemoModeState.getDemoFiles(path)
                )
            }
        }
        synchronized(cacheLock) {
            browseCache[normalized] = CachedBrowse(now, result)
            browseCache.values.removeAll { now - it.timestamp > BROWSE_CACHE_MAX_AGE_MS }
            trimCacheLocked()
        }
        return result
    }

    /** Least recently used listings go first so a long browsing session cannot grow the cache without bound. */
    private fun trimCacheLocked() {
        var items = browseCache.values.sumOf { it.listing.directories.size + it.listing.files.size }
        val iterator = browseCache.entries.iterator()
        while ((browseCache.size > MAX_CACHED_LISTINGS || items > MAX_CACHED_ITEMS) && browseCache.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            items -= eldest.value.listing.directories.size + eldest.value.listing.files.size
            iterator.remove()
        }
    }

    fun invalidateCache(path: String? = null) {
        synchronized(cacheLock) {
            if (path == null) {
                browseCache.clear()
            } else {
                browseCache.remove(normalizePath(path))
            }
        }
    }

    fun resolveWithinRoot(path: String): String =
        PathUtils.resolveWithinRoot(path, browseRoot)

    fun isWithinRoot(path: String): Boolean =
        PathUtils.isWithinRoot(path, browseRoot)

    fun parentWithinRoot(path: String): String? =
        PathUtils.parentWithinRoot(path, browseRoot)

    fun normalizePath(path: String): String =
        PathUtils.normalizeOr(path, browseRoot)

    fun clipboardCanPaste(selectionMode: Boolean): Boolean {
        return TransferClipboard.hasContent() && !selectionMode
    }
}

/** Served without asking the device again; pull to refresh and any change made here bypass it. */
private const val BROWSE_CACHE_TTL_MS = 5 * 60_000L

/** A remote folder older than this is shown from cache at once and checked again behind the scenes. */
private const val REVALIDATE_AFTER_MS = 15_000L

/** Nothing older than this stays in memory, fresh or not. */
private const val BROWSE_CACHE_MAX_AGE_MS = 10 * 60_000L
private const val MAX_CACHED_LISTINGS = 24
private const val MAX_CACHED_ITEMS = 60_000

data class BrowseListing(
    val directories: List<RemoteFileItem>,
    val files: List<RemoteFileItem>
)
