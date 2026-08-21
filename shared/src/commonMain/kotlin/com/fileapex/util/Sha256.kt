package com.fileapex.util

expect fun sha256Hex(data: ByteArray): String

expect fun sha256HexFile(absolutePath: String): String
