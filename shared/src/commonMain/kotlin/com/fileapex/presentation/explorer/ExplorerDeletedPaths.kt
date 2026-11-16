package com.fileapex.presentation.explorer

import com.fileapex.presentation.ExplorerUiState

/**
 * The browser after [deleted] paths are gone. A folder shown on the right of the two panes that was deleted, or one
 * inside a deleted folder, goes back to the pane's folder; reloading a folder that no longer exists would fail and
 * leave the old contents on screen.
 */
internal fun ExplorerUiState.afterDeleting(deleted: Collection<String>): ExplorerUiState {
    val gone = deleted.map { it.replace('\\', '/').trimEnd('/') }
    fun isGone(path: String): Boolean {
        val normalized = path.replace('\\', '/').trimEnd('/')
        return gone.any { normalized == it || normalized.startsWith("$it/") }
    }
    val shownGone = selectedFolderPath?.let(::isGone) == true || (selectedFolderPath != null && isGone(currentPath))
    if (!shownGone) return this
    return copy(
        selectedFolderPath = null,
        currentPath = panePath,
        contentDirectories = paneDirectories,
        contentFiles = paneFiles
    )
}
