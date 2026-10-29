package com.fileapex.platform

internal object ExtensionListing {
    /** True as soon as [isListed] says so; looks up to [attempts] times, [delayMs] apart. */
    fun awaitListed(
        isListed: () -> Boolean,
        attempts: Int = 8,
        delayMs: Long = 500,
        sleep: (Long) -> Unit = { Thread.sleep(it) }
    ): Boolean {
        repeat(attempts) { index ->
            if (isListed()) return true
            if (index < attempts - 1) sleep(delayMs)
        }
        return false
    }
}
