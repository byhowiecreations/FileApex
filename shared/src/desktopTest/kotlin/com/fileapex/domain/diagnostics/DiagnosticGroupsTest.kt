package com.fileapex.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticGroupsTest {
    private val saved = PeerDeviceDiagnostics(
        collectedAtEpochMs = 1,
        device = DeviceIdentityDiagnostics(make = "Apple", model = "Mac", kernelVersion = "26", osBuildVersion = "macOS 26"),
        processor = ProcessorDiagnostics(architecture = "arm64", hardware = "M4"),
        display = DisplayDiagnostics(resolution = "3024 x 1964")
    )
    private val shown = listOf(
        DeviceDetailsFieldId.Make, DeviceDetailsFieldId.Model, DeviceDetailsFieldId.CpuArchitecture,
        DeviceDetailsFieldId.DisplayResolution, DeviceDetailsFieldId.BatteryLevel, DeviceDetailsFieldId.Memory
    )

    @Test
    fun withNothingSavedEveryShownFieldsGroupIsAsked() {
        assertEquals(
            setOf(DiagnosticGroup.Identity, DiagnosticGroup.Processor, DiagnosticGroup.Display, DiagnosticGroup.Battery, DiagnosticGroup.Memory),
            DiagnosticGroups.toRequest(shown, null)
        )
    }

    @Test
    fun withTheFixedFactsSavedOnlyWhatChangesIsAsked() {
        assertEquals(setOf(DiagnosticGroup.Battery, DiagnosticGroup.Memory), DiagnosticGroups.toRequest(shown, saved))
    }

    @Test
    fun aFixedFactTheSavedCopyLacksIsAskedAgain() {
        val partial = saved.copy(device = DeviceIdentityDiagnostics(make = "Apple"))
        assertEquals(
            setOf(DiagnosticGroup.Identity, DiagnosticGroup.Battery, DiagnosticGroup.Memory),
            DiagnosticGroups.toRequest(shown, partial)
        )
    }

    @Test
    fun hiddenFieldsAreNeverAsked() {
        assertEquals(emptySet<DiagnosticGroup>(), DiagnosticGroups.toRequest(listOf(DeviceDetailsFieldId.Platform, DeviceDetailsFieldId.AppVersion), null))
        assertEquals(setOf(DiagnosticGroup.Network), DiagnosticGroups.toRequest(listOf(DeviceDetailsFieldId.NetworkType), saved))
    }

    @Test
    fun theListSurvivesTheWire() {
        val groups = setOf(DiagnosticGroup.Battery, DiagnosticGroup.Network)
        assertEquals(groups, DiagnosticGroups.decode(DiagnosticGroups.encode(groups)))
        assertEquals(emptySet<DiagnosticGroup>(), DiagnosticGroups.decode(DiagnosticGroups.encode(emptySet())))
        assertNull(DiagnosticGroups.decode(null))
        assertNull(DiagnosticGroups.decode(""))
        assertEquals(setOf(DiagnosticGroup.Battery), DiagnosticGroups.decode("Battery,Bogus"))
    }
}
