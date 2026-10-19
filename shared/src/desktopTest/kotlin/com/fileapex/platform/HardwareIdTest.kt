package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HardwareIdTest {
    @Test
    fun theHashIsTheSaltedSha256OfTheLowerCaseId() {
        // Python: hashlib.sha256(b"fileapex-machine|abc-123").hexdigest()
        val expected = "4168c2517fc078f47c04edf7bb7c5352134f5be9df9f77650556d49d0f11b029"
        assertEquals(expected, HardwareId.hash("abc-123"))
        assertEquals(expected, HardwareId.hash("  ABC-123 \n"))
    }

    @Test
    fun noIdMeansNoHash() {
        assertEquals("", HardwareId.hash(""))
        assertEquals("", HardwareId.hash("   "))
    }

    @Test
    fun theRawIdIsNotInTheHash() {
        assertFalse(HardwareId.hash("abc-123").contains("abc"))
    }

    @Test
    fun theMacHardwareUuidIsReadFromIoregOutput() {
        val output = """
            +-o Root  <class IORegistryEntry, id 0x100000100, retain 28>
              +-o MacBookPro18,3  <class IOPlatformExpertDevice, id 0x100000240, registered, matched, active, busy 0 (80 ms), retain 40>
                {
                  "IOPlatformSerialNumber" = "ABCDEF12345"
                  "IOPlatformUUID" = "5C1E2A3B-4D5E-6F70-8192-A3B4C5D6E7F8"
                }
        """.trimIndent()
        assertEquals("5C1E2A3B-4D5E-6F70-8192-A3B4C5D6E7F8", HardwareId.parseIoregUuid(output))
        assertEquals("", HardwareId.parseIoregUuid("nothing here"))
    }

    @Test
    fun theWindowsMachineGuidIsReadFromRegOutput() {
        val output = "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Cryptography\r\n    MachineGuid    REG_SZ    9f3c1a2b-7d4e-4a55-8b66-0123456789ab\r\n\r\n"
        assertEquals("9f3c1a2b-7d4e-4a55-8b66-0123456789ab", HardwareId.parseRegMachineGuid(output))
        assertEquals("", HardwareId.parseRegMachineGuid("ERROR: The system was unable to find the specified registry key"))
    }
}
