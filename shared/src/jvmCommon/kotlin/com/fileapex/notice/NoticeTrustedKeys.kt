package com.fileapex.notice

import java.util.Base64

/** Primary and backup Ed25519 public keys, base64 of the raw 32 bytes. Either one verifies a notice file. */
object NoticeTrustedKeys {
    private val PUBLIC_KEYS_BASE64: List<String> = listOf(
        "9HN7VISR6H6nKvLomrRv8D+NitB/VC/ZxbOJlUpBKK8=",
        "WafOQQk3zyjUIVINFdHKTik/5i1gW0vW1Jg6+q26LvQ="
    )

    fun all(): List<ByteArray> = PUBLIC_KEYS_BASE64.map { Base64.getDecoder().decode(it) }
}
