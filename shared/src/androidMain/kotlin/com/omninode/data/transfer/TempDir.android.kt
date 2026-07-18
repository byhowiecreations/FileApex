package com.omninode.data.transfer

internal actual fun defaultTempDir(): String {
    return System.getProperty("java.io.tmpdir") ?: "/tmp"
}
