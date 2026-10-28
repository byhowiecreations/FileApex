package com.fileapex.notice

/** Ed25519 check against any of [publicKeys] (raw 32-byte keys). */
expect fun noticeVerifySignature(message: ByteArray, signature: ByteArray, publicKeys: List<ByteArray>): Boolean

/** The built-in primary and backup public keys. */
expect fun noticeTrustedKeys(): List<ByteArray>
