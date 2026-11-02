package com.fileapex.membership

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormerIdTest {
    private fun key() = MembershipKeys.encode(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public)
    private val honorKey = key()
    private val pin = "ab".repeat(32)

    private fun state(vararg members: Pair<String, ChainMember>) = ChainState(5, "head", members.toMap())

    @Test fun anActiveMemberWithMyKeyUnderAnotherIdIsMyFormerId() {
        val s = state("fx-old" to ChainMember(honorKey, pin, true), "mac" to ChainMember(key(), pin, true))
        assertEquals("fx-old", IdentityChangeProof.formerId(s, selfId = "fx-new", signingKey = honorKey))
    }

    @Test fun nothingIsFormerWhenIAmAlreadyAnActiveMember() {
        val s = state("fx-old" to ChainMember(honorKey, pin, true), "fx-new" to ChainMember(honorKey, pin, true))
        assertNull(IdentityChangeProof.formerId(s, "fx-new", honorKey))
    }

    @Test fun aRemovedMemberWithMyKeyIsNotMyFormerId() {
        val s = state("fx-old" to ChainMember(honorKey, pin, false))
        assertNull(IdentityChangeProof.formerId(s, "fx-new", honorKey))
    }

    @Test fun aMemberWithAnotherKeyIsNotMyFormerId() {
        val s = state("mac" to ChainMember(key(), pin, true))
        assertNull(IdentityChangeProof.formerId(s, "fx-new", honorKey))
    }

    @Test fun aKeylessMemberCannotBeMyFormerId() {
        val s = state("fx-old" to ChainMember("legacy:fx-old", pin, true))
        assertNull(IdentityChangeProof.formerId(s, "fx-new", "legacy:fx-old"))
    }
}
