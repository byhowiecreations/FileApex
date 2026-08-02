package com.fileapex.data.files

/**
 * Matches Finder-default behavior: names starting with '.' are hidden.
 */
fun isHiddenDotName(name: String): Boolean = name.startsWith('.')
