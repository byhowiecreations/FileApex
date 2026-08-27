package com.fileapex.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun userHandleRoundTripFromAssertion() {
        val uid = "abcXYZ0123456789uidHandle"
        val handle = RestoreCredentialJson.base64Url(uid.encodeToByteArray())
        val assertion = """{"id":"cred","type":"public-key","response":{"userHandle":"$handle"}}"""
        assertEquals(uid, RestoreCredentialJson.userHandleFromAssertion(assertion))
        assertEquals(null, RestoreCredentialJson.emailFromAssertion(assertion))
    }

    @Test
    fun combinedHandleEncodesUidAndEmailWhenItFits() {
        val uid = "abcXYZ0123456789uidHandle"
        val email = "user@example.com"
        val handle = RestoreCredentialJson.base64Url(
            RestoreCredentialJson.userIdPayload(uid, email)
        )
        val assertion = """{"response":{"userHandle":"$handle"}}"""
        assertEquals(uid, RestoreCredentialJson.userHandleFromAssertion(assertion))
        assertEquals(email, RestoreCredentialJson.emailFromAssertion(assertion))
    }

    @Test
    fun longEmailFallsBackToUidOnlyPayload() {
        val uid = "abcXYZ0123456789uidHandle"
        val email = "a".repeat(80) + "@example.com"
        val payload = RestoreCredentialJson.userIdPayload(uid, email)
        assertTrue(payload.size <= RestoreCredentialJson.USER_ID_MAX_BYTES)
        val handle = RestoreCredentialJson.base64Url(payload)
        val assertion = """{"response":{"userHandle":"$handle"}}"""
        assertEquals(uid, RestoreCredentialJson.userHandleFromAssertion(assertion))
        assertEquals(null, RestoreCredentialJson.emailFromAssertion(assertion))
    }

    @Test
    fun missingUserHandleReturnsNull() {
        assertEquals(null, RestoreCredentialJson.userHandleFromAssertion("""{"id":"x"}"""))
        assertEquals(null, RestoreCredentialJson.userHandleFromAssertion("not-json"))
    }

    @Test
    fun base64UrlHasNoPadding() {
        val encoded = RestoreCredentialJson.base64Url(byteArrayOf(1, 2, 3))
        assertFalse(encoded.contains("="))
        assertNotNull(RestoreCredentialJson.decodeBase64Url(encoded))
    }
}
