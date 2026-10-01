package com.fileapex.presentation

import com.fileapex.domain.model.RemoteFileItem

enum class ExplorerSortMode { Name, Date, Size }

/** Filter and order for the current folder listing. Folders ignore [ExplorerSortMode.Size]. */
object ExplorerListOrdering {
    fun apply(items: List<RemoteFileItem>, query: String, mode: ExplorerSortMode): List<RemoteFileItem> {
        val needle = query.trim()
        val filtered = if (needle.isEmpty()) items else items.filter { it.name.contains(needle, ignoreCase = true) }
        val byName = compareBy<RemoteFileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val comparator = when (mode) {
            ExplorerSortMode.Name -> byName
            ExplorerSortMode.Date -> compareByDescending<RemoteFileItem> { it.lastModified }.then(byName)
            ExplorerSortMode.Size -> compareByDescending<RemoteFileItem> {
                if (it.isDirectory) 0L else it.sizeBytes
            }.then(byName)
        }
        return filtered.sortedWith(comparator)
    }
}
