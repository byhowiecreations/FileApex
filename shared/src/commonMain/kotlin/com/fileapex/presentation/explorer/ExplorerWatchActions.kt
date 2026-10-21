package com.fileapex.presentation.explorer

import androidx.lifecycle.viewModelScope
import com.fileapex.domain.browse.BrowserCoordinator
import com.fileapex.platform.DirectoryWatch
import com.fileapex.platform.watchLocalDirectory
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.presentation.ExplorerViewModel
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ExplorerWatchActions(
    private val _uiState: MutableStateFlow<ExplorerUiState>,
    private val viewModelScope: CoroutineScope,
    private val target: BrowseTarget,
    private val browser: BrowserCoordinator,
    private val browseRoot: String,
    private val currentBrowseGeneration: () -> Int,
    private val reloadListing: (Boolean) -> Unit
) {
    private val browseGeneration: Int
        get() = currentBrowseGeneration()

    var localWatchDebounce: Job? = null

    var contentWatch: DirectoryWatch? = null

    var paneWatch: DirectoryWatch? = null

    private var watchedContent: String? = null

    private var watchedPane: String? = null

    fun syncLocalWatch(contentPath: String, panePath: String) {
        if (target !is BrowseTarget.Local) return
        if (contentPath == watchedContent && panePath == watchedPane) return
        contentWatch?.close()
        paneWatch?.close()
        contentWatch = null
        paneWatch = null
        watchedContent = contentPath
        watchedPane = panePath
        contentWatch = openLocalWatch(contentPath)
        if (panePath != contentPath) {
            paneWatch = openLocalWatch(panePath)
        }
    }

    private fun openLocalWatch(path: String): DirectoryWatch? {
        if (path.isBlank()) return null
        return runCatching { watchLocalDirectory(path, ::onLocalFolderChanged) }.getOrNull()
    }

    private fun onLocalFolderChanged() {
        val generation = browseGeneration
        localWatchDebounce?.cancel()
        localWatchDebounce = viewModelScope.launch {
            delay(400)
            if (generation != browseGeneration) return@launch
            if (target !is BrowseTarget.Local) return@launch
            reloadListing(false)
        }
    }

    /** A remote folder shown from cache changed on the device: swap in the fresh listing unless the user is mid-navigation. */
    fun onFolderRevalidated(normalizedPath: String) {
        val state = _uiState.value
        if (state.isLoading) return
        val isContent = browser.normalizePath(state.currentPath) == normalizedPath
        val isPane = browser.normalizePath(state.panePath.ifBlank { browseRoot }) == normalizedPath
        if (!isContent && !isPane) return
        val generation = browseGeneration
        viewModelScope.launch {
            val listing = cancellableCatching { browser.listAt(normalizedPath) }.getOrNull() ?: return@launch
            if (generation != browseGeneration) return@launch
            _uiState.update {
                it.copy(
                    paneDirectories = if (isPane) listing.directories else it.paneDirectories,
                    paneFiles = if (isPane) listing.files else it.paneFiles,
                    contentDirectories = if (isContent) listing.directories else it.contentDirectories,
                    contentFiles = if (isContent) listing.files else it.contentFiles
                )
            }
        }
    }
}
