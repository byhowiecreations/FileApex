package com.fileapex.util

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Work for one device never waits for work for another. Each key (a device id) gets its own coroutine: a second request
 * for a key that is already running is not queued behind a lock, it asks the running job to go round once more when it
 * is done, so a burst of events costs one extra run. A slow, dead or failing key therefore holds up nobody else.
 */
class PerKeyRunner(private val scope: CoroutineScope) {
    private val lock = Any()
    private val running = HashMap<String, Job>()
    private val again = HashSet<String>()

    fun launch(key: String, work: suspend () -> Unit) {
        synchronized(lock) {
            if (running[key]?.isActive == true) {
                again += key
                return
            }
            running[key] = scope.launch {
                val me = coroutineContext[Job]
                try {
                    var goAgain = true
                    while (goAgain) {
                        synchronized(lock) { again.remove(key) }
                        try {
                            work()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            println("PerKeyRunner: work for a device failed - ${error::class.simpleName}")
                        }
                        // Deciding to stop and leaving the table is one step, so a request made in between is never lost.
                        goAgain = synchronized(lock) {
                            if (again.remove(key)) {
                                true
                            } else {
                                if (running[key] === me) running.remove(key)
                                false
                            }
                        }
                    }
                } finally {
                    synchronized(lock) { if (running[key] === me) running.remove(key) }
                }
            }
        }
    }

    fun isRunning(key: String): Boolean = synchronized(lock) { running[key]?.isActive == true }
}
