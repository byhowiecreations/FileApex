package com.fileapex.security

/** Runs [block] with secret bytes and zeroes them afterwards, also when the block throws. */
internal inline fun <T> ByteArray.useAndWipe(block: (ByteArray) -> T): T =
    try { block(this) } finally { fill(0) }

internal inline fun <T> ByteArray?.useAndWipeOr(default: T, block: (ByteArray) -> T): T =
    if (this == null) default else useAndWipe(block)
