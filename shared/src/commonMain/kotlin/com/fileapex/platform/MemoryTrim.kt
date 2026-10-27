package com.fileapex.platform

import java.lang.ref.WeakReference

fun interface Trimmable {
    fun trimMemory()
}

/**
 * Called when the system asks for memory back (Android `onTrimMemory`, UI hidden or worse). Owners register
 * themselves weakly, so a closed screen's cache is not kept alive just to be trimmed.
 */
object MemoryTrim {
    private val owners = mutableListOf<WeakReference<Trimmable>>()

    internal val defaultReport: (String) -> Unit = { Diagnostics.log(it) }
    internal var report: (String) -> Unit = defaultReport

    fun register(owner: Trimmable) {
        synchronized(owners) {
            owners.removeAll { it.get() == null }
            if (owners.none { it.get() === owner }) owners += WeakReference(owner)
        }
    }

    fun release() {
        val live = synchronized(owners) {
            owners.removeAll { it.get() == null }
            owners.mapNotNull { it.get() }
        }
        report("memory trim: asking ${live.size} caches to release")
        live.forEach { owner ->
            try {
                owner.trimMemory()
            } catch (e: RuntimeException) {
                report("memory trim failed in ${owner::class.simpleName}: ${e.message}")
            }
        }
    }
}
