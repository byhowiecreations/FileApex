package com.fileapex.util

/** A value built once, however many threads ask for it at the same time. */
class SingleInstance<T : Any>(private val build: () -> T) {
    @Volatile
    private var value: T? = null

    fun get(): T = value ?: synchronized(this) { value ?: build().also { value = it } }
}
