package com.fileapex.platform

import android.os.Build
import android.os.FileObserver
import java.io.File

actual fun watchLocalDirectory(absolutePath: String, onChange: () -> Unit): DirectoryWatch {
    val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MODIFY or
        FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.ATTRIB
    val observer = if (Build.VERSION.SDK_INT >= 29) {
        object : FileObserver(File(absolutePath), mask) {
            override fun onEvent(event: Int, path: String?) {
                onChange()
            }
        }
    } else {
        legacyObserver(absolutePath, mask, onChange)
    }
    observer.startWatching()
    return DirectoryWatch { observer.stopWatching() }
}

@Suppress("DEPRECATION")
private fun legacyObserver(absolutePath: String, mask: Int, onChange: () -> Unit): FileObserver {
    return object : FileObserver(absolutePath, mask) {
        override fun onEvent(event: Int, path: String?) {
            onChange()
        }
    }
}
