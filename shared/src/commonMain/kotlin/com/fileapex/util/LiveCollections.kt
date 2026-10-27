package com.fileapex.util

/**
 * Copies a collection that other threads may be changing, such as the keys or values of a ConcurrentHashMap.
 * Kotlin's toList() and toSet() read size and then call iterator().next() when it is 1, which throws
 * NoSuchElementException if the element is removed in between; ArrayList(collection) copies through toArray().
 */
fun <T> Collection<T>.copyOfLive(): List<T> = ArrayList(this)
