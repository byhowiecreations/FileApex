package com.fileapex.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Asks the local file screen to open a folder. The app shell switches to that screen and the explorer reads the path. */
object LocalExplorerNavigation {
    private val _folder = MutableStateFlow<String?>(null)
    val folder: StateFlow<String?> = _folder.asStateFlow()

    fun showFile(absolutePath: String) {
        val folder = parentOf(absolutePath)
        if (folder.isNotEmpty()) _folder.value = folder
    }

    fun showFolder(absolutePath: String) {
        val folder = absolutePath.trim().trimEnd('/', '\\')
        if (folder.isNotEmpty()) _folder.value = folder
    }

    fun clear(path: String) {
        if (_folder.value == path) _folder.value = null
    }

    private fun parentOf(absolutePath: String): String {
        val trimmed = absolutePath.trim().trimEnd('/', '\\')
        val slash = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        return if (slash > 0) trimmed.substring(0, slash) else trimmed
    }
}
