package com.fileapex.platform

import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds

actual fun watchLocalDirectory(absolutePath: String, onChange: () -> Unit): DirectoryWatch {
    val service = FileSystems.getDefault().newWatchService()
    val dir = Paths.get(absolutePath)
    dir.register(
        service,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_DELETE,
        StandardWatchEventKinds.ENTRY_MODIFY
    )
    val thread = Thread({
        try {
            while (!Thread.currentThread().isInterrupted) {
                val key = service.take()
                var changed = false
                for (event in key.pollEvents()) {
                    if (event.kind() != StandardWatchEventKinds.OVERFLOW) changed = true
                }
                val valid = key.reset()
                if (changed) onChange()
                if (!valid) break
            }
        } catch (_: InterruptedException) {
        } catch (_: ClosedWatchServiceException) {
        }
    }, "fileapex-dir-watch")
    thread.isDaemon = true
    thread.start()
    return DirectoryWatch {
        service.close()
    }
}
