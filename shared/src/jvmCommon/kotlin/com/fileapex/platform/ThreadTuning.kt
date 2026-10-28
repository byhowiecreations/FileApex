package com.fileapex.platform

/**
 * Idle worker threads of the coroutine pool live 60 seconds by default, so one burst of blocking work (a subnet sweep
 * at start) leaves dozens of threads parked for a minute or more. Five seconds lets them go as soon as the work ends and
 * they are created again when something needs them. The scheduler reads this once, so it must run before the first
 * coroutine is dispatched: first thing in the Android Application and in the desktop main.
 */
object ThreadTuning {
    const val SCHEDULER_KEEP_ALIVE_KEY = "kotlinx.coroutines.scheduler.keep.alive.sec"
    private const val SCHEDULER_KEEP_ALIVE_SECONDS = "5"

    fun apply() {
        if (System.getProperty(SCHEDULER_KEEP_ALIVE_KEY) == null) System.setProperty(SCHEDULER_KEEP_ALIVE_KEY, SCHEDULER_KEEP_ALIVE_SECONDS)
    }
}
