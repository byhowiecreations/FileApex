package com.fileapex.domain.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTlsTest {
    private val pin = "0123456789abcdef".repeat(4)

    private fun payload(tlsPin: String = pin, tlsPort: Int = 8443) = PairingPayloadFactory.create(
        deviceId = "dev-1", deviceName = "Desk", host = "192.168.1.9", port = 8080, rootPath = "/",
        pairingCode = "123456", tlsPin = tlsPin, tlsPort = tlsPort
    )

    @Test
    fun qrRoundTripCarriesTrustedPinAndPort() {
        val parsed = PairingPayload.parse(payload().toQrText())
        assertEquals(pin, parsed.tlsPin)
        assertEquals(8443, parsed.tlsPort)
        assertTrue(parsed.tlsPinTrusted)
    }

    @Test
    fun qrWithoutTlsParsesAsUntrusted() {
        val parsed = PairingPayload.parse(payload(tlsPin = "", tlsPort = 0).toQrText())
        assertEquals("", parsed.tlsPin)
        assertFalse(parsed.tlsPinTrusted)
    }

    @Test
    fun malformedPinOrPortIsDropped() {
        val base = payload(tlsPin = "", tlsPort = 0).toQrText()
        assertEquals("", PairingPayload.parse("$base&tp=zz&tport=8443").tlsPin)
        val badPort = PairingPayload.parse("$base&tp=$pin&tport=99999")
        assertEquals(0, badPort.tlsPort)
        assertFalse(badPort.tlsPinTrusted)
    }

    @Test
    fun beaconDerivedPayloadIsNeverTrusted() {
        val beacon = PairingBeacon(
            deviceName = "Desk", ipAddress = "192.168.1.9", port = 8080, pairingCode = "123456",
            timestamp = 1L, deviceId = "dev-1", tlsPin = pin, tlsPort = 8443
        )
        val fromBeacon = beacon.toPairingPayload()
        assertEquals(pin, fromBeacon.tlsPin)
        assertFalse(fromBeacon.tlsPinTrusted)
    }

    @Test
    fun jsonWireNeverCarriesTrust() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val encoded = json.encodeToString(PairingPayload.serializer(), payload().copy(tlsPinTrusted = true))
        assertFalse(PairingPayload.parse(encoded).tlsPinTrusted)
    }
}
