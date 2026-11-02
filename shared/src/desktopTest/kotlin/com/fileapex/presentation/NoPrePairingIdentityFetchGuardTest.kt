package com.fileapex.presentation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** A removed device gets `401 revoked` on an identity request, so nothing may ask for it before the pairing handshake has run. */
class NoPrePairingIdentityFetchGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun theJoinerAsksForNoIdentityBeforeTheSecureExchange() {
        val text = File(root, "shared/src/commonMain/kotlin/com/fileapex/presentation/devices/DevicesPairingActions.kt").readText()
        val exchange = text.indexOf("securePairingQrExchange")
        assertTrue("the secure exchange is not in the file", exchange > 0)
        val firstFetch = text.indexOf("fetchPeerNodeState")
        assertTrue("an identity request comes before the secure exchange", firstFetch > exchange)
    }
}
