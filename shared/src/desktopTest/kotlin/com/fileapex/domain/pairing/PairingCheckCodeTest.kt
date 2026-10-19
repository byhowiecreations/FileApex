package com.fileapex.domain.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairingCheckCodeTest {
    @Test
    fun bothDevicesComputeTheSameCodeFromTheSameInputs() {
        // The same vectors are asserted in the Go daemon's tests (pair_check_test.go).
        assertEquals("862 634", PairingCheckCode.derive("aabbcc", "ddeeff", "123456"))
        assertEquals("962 314", PairingCheckCode.derive("0123456789abcdef", "fedcba9876543210", "000001"))
    }

    @Test
    fun spacesInThePairingCodeDoNotMatter() {
        assertEquals("862 634", PairingCheckCode.derive("aabbcc", "ddeeff", "123 456"))
    }

    @Test
    fun swappingHostAndJoinerChangesTheCode() {
        assertEquals("463 093", PairingCheckCode.derive("ddeeff", "aabbcc", "123456"))
    }

    @Test
    fun aDifferentKeyOrCodeChangesTheCode() {
        val base = PairingCheckCode.derive("aabbcc", "ddeeff", "123456")
        assertNotEquals(base, PairingCheckCode.derive("aabbcd", "ddeeff", "123456"))
        assertNotEquals(base, PairingCheckCode.derive("aabbcc", "ddeeff", "123457"))
    }

    @Test
    fun theKeyFallsBackToTheDeviceIdWhenThereIsNoPin() {
        assertEquals("abc123", PairingCheckCode.key(pin = " ABC123 ", deviceId = "dev"))
        assertEquals("dev", PairingCheckCode.key(pin = "", deviceId = " dev "))
    }

    @Test
    fun theCodeIsSixDigitsInTwoGroups() {
        val code = PairingCheckCode.derive("k1", "k2", "000000")
        assertEquals(Regex("\\d{3} \\d{3}").matches(code), true)
    }
}
