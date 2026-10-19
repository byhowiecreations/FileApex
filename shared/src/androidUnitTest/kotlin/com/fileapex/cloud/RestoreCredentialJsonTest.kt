package com.fileapex.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreCredentialJsonTest {

    @Test
    fun creationJsonEncodesUserIdAndEmail() {
        val json = RestoreCredentialJson.creationOptions(
            userId = "firebaseUid28Chars________",
            email = "user@example.com",
            challengeB64 = "abc123"
        )
        assertTrue(json.contains("\"id\":\"${RestoreCredentialJson.RP_ID}\""))
        assertTrue(json.contains("user@example.com"))
        assertTrue(json.contains("\"challenge\":\"abc123\""))
        val emailHandle = RestoreCredentialJson.base64Url(
            RestoreCredentialJson.userIdPayload("firebaseUid28Chars________", "user@example.com")
        )
        assertTrue(json.contains(emailHandle))
    }

    @Test
    fun requestJsonUsesRestoreRpId() {
        val json = RestoreCredentialJson.requestOptions("chal")
        assertTrue(json.contains("\"rpId\":\"${RestoreCredentialJson.RP_ID}\""))
        assertTrue(json.contains("\"challenge\":\"chal\""))
    }

    @Test
    fun base64UrlHasNoPadding() {
        val encoded = RestoreCredentialJson.base64Url(byteArrayOf(1, 2, 3))
        assertFalse(encoded.contains("="))
    }
}
