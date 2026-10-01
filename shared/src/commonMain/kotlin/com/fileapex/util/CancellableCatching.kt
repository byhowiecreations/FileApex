package com.fileapex.util

import kotlin.coroutines.cancellation.CancellationException

/** [runCatching] that rethrows coroutine cancellation instead of turning it into a failure. */
inline fun <T> cancellableCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
