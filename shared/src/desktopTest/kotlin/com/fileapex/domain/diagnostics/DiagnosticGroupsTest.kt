package com.fileapex.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticGroupsTest {
    private val shown = listOf(
        DeviceDetailsFieldId.Make, DeviceDetailsFieldId.Model, DeviceDetailsFieldId.CpuArchitecture,
        DeviceDetailsFieldId.DisplayResolution, DeviceDetailsFieldId.BatteryLevel, DeviceDetailsFieldId.Memory
    )

    @Test
    fun everyShownFieldsGroupIsAskedForEveryTime() {
        assertEquals(
            setOf(DiagnosticGroup.Identity, DiagnosticGroup.Processor, DiagnosticGroup.Display, DiagnosticGroup.Battery, DiagnosticGroup.Memory),
            DiagnosticGroups.toRequest(shown)
        )
    }

    @Test
    fun aFieldWithNoGroupAsksForNothing() {
        assertEquals(emptySet<DiagnosticGroup>(), DiagnosticGroups.toRequest(listOf(DeviceDetailsFieldId.Platform, DeviceDetailsFieldId.AppVersion)))
    }

    @Test
    fun onlyTheGroupsOfShownFieldsAreAsked() {
        assertEquals(setOf(DiagnosticGroup.Network), DiagnosticGroups.toRequest(listOf(DeviceDetailsFieldId.NetworkType)))
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
