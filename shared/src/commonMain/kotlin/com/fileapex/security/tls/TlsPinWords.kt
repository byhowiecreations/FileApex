package com.fileapex.security.tls

import java.security.MessageDigest

object TlsPinWords {
    private val WORDS = listOf(
        "acorn", "amber", "anchor", "apple", "arrow", "aspen", "badge", "bamboo", "beach", "berry", "bison", "breeze",
        "cabin", "candle", "canyon", "cedar", "cherry", "cloud", "clover", "coral", "daisy", "delta", "dune", "eagle",
        "ember", "falcon", "fern", "field", "flint", "forest", "garden", "ginger", "glacier", "harbor", "hazel", "heron",
        "iris", "island", "jade", "jungle", "kettle", "lagoon", "lemon", "lotus", "maple", "meadow", "mint", "moss",
        "nectar", "oasis", "olive", "orchid", "otter", "panda", "pebble", "pine", "quartz", "raven", "river", "sage",
        "tiger", "tulip", "willow", "zephyr"
    )

    const val COUNT = 6

    /** Six words from the pin's SHA-256 (36 bits). The hex stays the exact value; the words are for reading aloud. */
    fun of(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.lowercase().toByteArray())
        return (0 until COUNT).joinToString("-") { WORDS[(digest[it].toInt() and 0xFF) % WORDS.size] }
    }

    internal fun wordList(): List<String> = WORDS
}
