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
