package com.fileapex.platform

fun interface DirectoryWatch {
    fun close()
}

/** Notifies when entries in this directory change. The callback may run off the UI thread. */
expect fun watchLocalDirectory(absolutePath: String, onChange: () -> Unit): DirectoryWatch
