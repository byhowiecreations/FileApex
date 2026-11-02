package com.fileapex.i18n

import com.fileapex.util.NetworkUtils
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingErrorsTest {
    @Test
    fun timeoutMapsToLocalizedCopy() {
        assertEquals(
            AppI18n.t("error_peer_timeout"),
            UserFacingErrors.message(SocketTimeoutException("connect timed out"), "send_failed")
        )
    }

    @Test
    fun wrappedRefusedConnectionIsFoundInCauseChain() {
        val wrapped = IllegalStateException("send failed", ConnectException("Connection refused"))
        assertEquals(AppI18n.t("error_peer_unreachable"), UserFacingErrors.message(wrapped, "send_failed"))
    }

    @Test
    fun diskFullIsRecognizedFromIoMessage() {
        assertEquals(
            AppI18n.t("error_disk_full"),
            UserFacingErrors.message(IOException("write failed: ENOSPC (No space left on device)"), "download_failed")
        )
    }

    @Test
    fun aPeerThatRefusesOurCertificateIsExplainedInsteadOfShowingTheTlsText() {
        val android = javax.net.ssl.SSLProtocolException(
            "Read error: ssl=0xb4000073664339d8: Failure in SSL library, usually a protocol error\n" +
                "error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN (tls_record.cc:489)"
        )
        val desktop = javax.net.ssl.SSLHandshakeException("Received fatal alert: certificate_unknown")
        val wrapped = IllegalStateException("fetch failed", desktop)
        val expected = AppI18n.t("error_peer_not_trusted")
        assertTrue(expected != "error_peer_not_trusted")
        assertEquals(expected, UserFacingErrors.message(android, "unable_to_reach_device"))
        assertEquals(expected, UserFacingErrors.message(desktop, "unable_to_reach_device"))
        assertEquals(expected, UserFacingErrors.message(wrapped, "unable_to_reach_device"))
    }

    @Test
    fun onlyACertificateRefusalCountsAsThePeerNotTrustingUs() {
        assertTrue(UserFacingErrors.isPeerRefusal(javax.net.ssl.SSLHandshakeException("Received fatal alert: certificate_unknown")))
        assertTrue(UserFacingErrors.isPeerRefusal(IllegalStateException("x", javax.net.ssl.SSLProtocolException("SSLV3_ALERT_BAD_CERTIFICATE"))))
        assertFalse(UserFacingErrors.isPeerRefusal(SocketTimeoutException("connect timed out")))
        assertFalse(UserFacingErrors.isPeerRefusal(ConnectException("Connection refused")))
        assertFalse(UserFacingErrors.isPeerRefusal(IllegalStateException("")))
    }

    @Test
    fun alreadyLocalizedMessagePassesThroughAndBlankUsesFallback() {
        assertEquals("Incorrect PIN", UserFacingErrors.message(IllegalStateException("Incorrect PIN"), "pairing_failed"))
        assertEquals(AppI18n.t("pairing_failed"), UserFacingErrors.message(IllegalStateException(""), "pairing_failed"))
    }

    @Test
    fun rawPersistedTextMapping() {
        assertEquals(AppI18n.t("error_connection_lost"), UserFacingErrors.messageForRaw("Connection reset by peer"))
        assertNull(UserFacingErrors.messageForRaw("Nothing to queue"))
    }

    @Test
    fun sameSubnetBindFailureStopsCandidateFanOut() {
        assertFalse(NetworkUtils.shouldTryNextBindCandidate("192.168.1.20", "192.168.1.44"))
        assertTrue(NetworkUtils.shouldTryNextBindCandidate("10.0.0.5", "192.168.1.44"))
    }
}
