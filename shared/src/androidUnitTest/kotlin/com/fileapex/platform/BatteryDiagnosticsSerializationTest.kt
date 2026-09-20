package com.fileapex.platform

import com.fileapex.domain.diagnostics.BatteryDiagnostics
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryDiagnosticsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun serializesAndDeserializesLowPowerMode() {
        val diag = BatteryDiagnostics(
            levelPercent = 34,
            chargingState = "Discharging",
            temperatureCelsius = 31.5,
            lowPowerMode = true
        )
        val encoded = json.encodeToString(BatteryDiagnostics.serializer(), diag)
        assertTrue(encoded.contains("\"lowPowerMode\":true"))

        val decoded = json.decodeFromString(BatteryDiagnostics.serializer(), encoded)
        assertEquals(34, decoded.levelPercent)
        assertEquals("Discharging", decoded.chargingState)
        assertTrue(decoded.lowPowerMode)
    }

    @Test
    fun backwardsCompatibleWithLegacyPayloadsMissingLowPowerMode() {
        val legacyJson = """{"levelPercent":88,"chargingState":"AC"}"""
        val decoded = json.decodeFromString(BatteryDiagnostics.serializer(), legacyJson)
        assertEquals(88, decoded.levelPercent)
        assertEquals("AC", decoded.chargingState)
        assertFalse(decoded.lowPowerMode)
    }
}
