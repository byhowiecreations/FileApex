package com.fileapex.notice

actual fun noticeVerifySignature(message: ByteArray, signature: ByteArray, publicKeys: List<ByteArray>): Boolean =
    NoticeVerifier.verify(message, signature, publicKeys)

actual fun noticeTrustedKeys(): List<ByteArray> = NoticeTrustedKeys.all()
