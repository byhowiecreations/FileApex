package com.fileapex.security

/** Overwrites key material once it is no longer needed. A JVM copy made earlier (for example inside SecretKeySpec) is not reached. */
internal fun ByteArray.wipe() = fill(0)
