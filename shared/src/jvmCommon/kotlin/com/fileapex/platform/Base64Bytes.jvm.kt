package com.fileapex.platform

import java.util.Base64
import com.fileapex.util.cancellableCatching

actual fun decodeBase64Bytes(encoded: String): ByteArray? {
    return cancellableCatching("Base64Bytes decodeBase64Bytes") {
        Base64.getDecoder().decode(encoded.trim())
    }.getOrNull()
}
