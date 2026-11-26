package com.fileapex.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs one job at a time in [scope]: starting a new one cancels the previous, and [cancel] stops it. Used where a late
 * result must never overwrite a newer request or reappear after the user closed the screen.
 */
class LatestJob(private val scope: CoroutineScope) {
    private var current: Job? = null

    @Synchronized
    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        current?.cancel()
        return scope.launch(block = block).also { current = it }
    }

    @Synchronized
    fun cancel() {
        current?.cancel()
        current = null
    }
}
