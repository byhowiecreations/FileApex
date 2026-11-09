package com.fileapex.platform

import java.io.File

/** A staged file or folder with its kind read once at staging time, so drawing the list never touches the disk. */
data class DesktopStagedEntry(val path: String, val name: String, val isDirectory: Boolean)

/**
 * State model for drop box file staging, destination resolution, and send progress.
 * [inspect] reads the disk: call it off the UI thread, then [append] the result.
 */
class DesktopDropBoxStagingState {
    var targetDeviceIds: List<String> = emptyList()
    var singleDeviceName: String? = null
    var stagedEntries: List<DesktopStagedEntry> = emptyList()
        private set
    var isSending: Boolean = false
    var isPinned: Boolean = false

    val stagedPaths: List<String>
        get() = stagedEntries.map { it.path }

    val destinationSubtitle: String?
        get() = if (targetDeviceIds.size == 1 && !singleDeviceName.isNullOrBlank()) {
            singleDeviceName
        } else null

    fun formatDestinations(formatter: (count: Int) -> String): String {
        val single = destinationSubtitle
        if (single != null) return single
        return formatter(targetDeviceIds.size)
    }

    fun appendValidPaths(paths: List<String>) = append(inspect(paths))

    /** Adds entries that are not staged yet; cheap, so it can run on the UI thread. */
    fun append(entries: List<DesktopStagedEntry>) {
        val known = stagedEntries.mapTo(HashSet()) { it.path }
        val added = entries.filter { known.add(it.path) }
        if (added.isEmpty()) return
        stagedEntries = stagedEntries + added
    }

    fun removePath(path: String) {
        stagedEntries = stagedEntries.filterNot { it.path == path }
    }

    fun clear() {
        stagedEntries = emptyList()
        isSending = false
    }

    companion object {
        /** Reads the disk for each path and keeps the ones that exist. Call it off the UI thread. */
        fun inspect(paths: List<String>): List<DesktopStagedEntry> =
            paths.filter { it.isNotBlank() }
                .map(::File)
                .filter { it.exists() }
                .map { DesktopStagedEntry(it.path, it.name, it.isDirectory) }
    }
}
