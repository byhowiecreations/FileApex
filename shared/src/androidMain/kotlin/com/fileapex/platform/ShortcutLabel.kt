package com.fileapex.platform

/** Android refuses a shortcut with an empty label, and one such shortcut ended the whole app at start-up. */
internal object ShortcutLabel {
    fun of(name: String, fallback: String, max: Int): String = name.ifBlank { fallback }.take(max)
}
