package com.fileapex.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that rethrows coroutine cancellation instead of turning it into a failure.
 *
 * With [what] a failure is logged by that step name and the exception class only (never the message, which can hold
 * paths or addresses). The same failure of the same step is logged once until the step succeeds again, so a step that
 * keeps failing does not fill the log. Without [what] nothing is logged: the caller handles the failure itself.
 */
inline fun <T> cancellableCatching(what: String? = null, block: () -> T): Result<T> =
    try {
        Result.success(block()).also { if (what != null) stepSucceeded(what) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        if (what != null) logFailureOnce(what, error)
        Result.failure(error)
    }

@PublishedApi
internal val lastFailures = java.util.concurrent.ConcurrentHashMap<String, String>()

@PublishedApi
internal fun stepSucceeded(what: String) {
    lastFailures.remove(what)
}

/** The once-until-it-changes log for a step that handles its own exception. */
fun logFailureOnce(what: String, error: Throwable) {
    val reason = error::class.simpleName.orEmpty()
    if (lastFailures.put(what, reason) != reason) println("$what failed - $reason")
}
