package com.fileapex.security.tls

import com.fileapex.data.device.TlsPinEntry
import java.security.cert.CertificateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A removed device that comes back must reach the point of being told so, and nothing else. */
class RevokedPeerPinsTest {
    private fun row(id: String, pin: String, removed: Boolean) = TlsPinEntry(id, pin, alt = "", port = 8081, revoked = removed, signed = true)

    @Test
    fun aRemovedDevicesPinIsKnownAsRevokedAndNeverAsAPairedPeer() {
        val directory = RosterTlsPeerDirectory()
        directory.refresh(listOf(row("mac", "aa11", removed = false), row("honor", "bb22", removed = true), row("gone", "", removed = true)))
        assertEquals("mac", directory.deviceIdForPin("aa11"))
        assertNull(directory.deviceIdForPin("bb22"))
        assertEquals("honor", directory.revokedDeviceIdForPin("bb22"))
        assertNull(directory.revokedDeviceIdForPin("aa11"))
        assertNull(directory.revokedDeviceIdForPin(""))
    }

    @Test
    fun theTrustManagerLetsARevokedPinCompleteTheHandshakeButNotAStranger() {
        val revoked = TlsIdentityFactory.generate()
        val stranger = TlsIdentityFactory.generate()
        val directory = RosterTlsPeerDirectory()
        directory.refresh(listOf(row("honor", revoked.pin, removed = true)))
        val trust = PinningClientTrustManager(directory)
        trust.checkClientTrusted(arrayOf(revoked.certificate), "EC", null as java.net.Socket?)
        try {
            trust.checkClientTrusted(arrayOf(stranger.certificate), "EC", null as java.net.Socket?)
            fail("a stranger's key must not complete the handshake")
        } catch (expected: CertificateException) {
            assertTrue(expected.message.orEmpty().contains("not pinned"))
        }
    }
}
