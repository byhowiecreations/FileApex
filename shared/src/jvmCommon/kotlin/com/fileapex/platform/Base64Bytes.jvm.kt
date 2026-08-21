package com.fileapex.platform

import java.util.Base64

actual fun decodeBase64Bytes(encoded: String): ByteArray? {
    return runCatching {
        Base64.getDecoder().decode(encoded.trim())
    }.getOrNull()
}
