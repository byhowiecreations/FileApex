package com.fileapex.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDetailsPreferencesTest {
    private val basicOrder = listOf(
        DeviceDetailsFieldId.Platform, DeviceDetailsFieldId.Make, DeviceDetailsFieldId.Model, DeviceDetailsFieldId.AppVersion,
        DeviceDetailsFieldId.OsBuild, DeviceDetailsFieldId.Kernel, DeviceDetailsFieldId.BatteryLevel, DeviceDetailsFieldId.Storage,
        DeviceDetailsFieldId.Memory, DeviceDetailsFieldId.CpuArchitecture, DeviceDetailsFieldId.CpuHardware
    )

    @Test
    fun aNewInstallShowsOnlyTheBasicFieldsInTheAgreedOrder() {
        val prefs = DeviceDetailsDisplayPreferences.decode("")
        assertEquals(basicOrder, prefs.visibleFieldIds())
        assertEquals(basicOrder, prefs.normalized().fields.take(basicOrder.size).map { DeviceDetailsFieldId.valueOf(it.id) })
    }

    @Test
    fun everyFieldIsStillListedSoTheAdvancedOnesCanBeTurnedOn() {
        val prefs = DeviceDetailsDisplayPreferences.defaults().normalized()
        assertEquals(DeviceDetailsFieldId.entries.size, prefs.fields.size)
        assertTrue(prefs.fields.filter { !DeviceDetailsFieldId.valueOf(it.id).isBasic }.none { it.visible })
    }

    @Test
    fun anExistingUsersOrderAndChoicesAreKept() {
        // Saved before AppVersion existed: everything on, in a custom order with the battery first.
        val old = (listOf(DeviceDetailsFieldId.BatteryLevel) + DeviceDetailsFieldId.entries.filter { it != DeviceDetailsFieldId.BatteryLevel && it != DeviceDetailsFieldId.AppVersion })
            .map { DeviceDetailsFieldPreference(it.name, visible = it != DeviceDetailsFieldId.Ssid) }
        val prefs = DeviceDetailsDisplayPreferences(old).normalized()
        assertEquals(DeviceDetailsFieldId.BatteryLevel.name, prefs.fields.first().id)
        assertFalse(prefs.fields.first { it.id == DeviceDetailsFieldId.Ssid.name }.visible)
        assertTrue(prefs.fields.first { it.id == DeviceDetailsFieldId.Collected.name }.visible)
        assertEquals(old.map { it.id }, prefs.fields.take(old.size).map { it.id })
    }

    @Test
    fun theNewVersionRowIsAddedAtTheEndForAnExistingUser() {
        val old = DeviceDetailsFieldId.entries.filter { it != DeviceDetailsFieldId.AppVersion }.map { DeviceDetailsFieldPreference(it.name, true) }
        val prefs = DeviceDetailsDisplayPreferences(old).normalized()
        assertEquals(DeviceDetailsFieldId.AppVersion.name, prefs.fields.last().id)
        assertTrue(prefs.fields.last().visible)
    }

    @Test
    fun appVersionReadsWithItsSourceAndBuild() {
        assertEquals("-", DeviceDiagnosticsFormatter.appVersionSummary(AppDiagnostics()))
        assertEquals("0.19.2", DeviceDiagnosticsFormatter.appVersionSummary(AppDiagnostics(versionName = "0.19.2")))
        val summary = DeviceDiagnosticsFormatter.appVersionSummary(AppDiagnostics("0.19.2", 210, "github"))
        assertTrue(summary, summary.startsWith("0.19.2 (") && summary.contains("GitHub") && summary.contains("210"))
        assertTrue(DeviceDiagnosticsFormatter.appVersionSummary(AppDiagnostics("0.19.2", 210, "fdroid")).contains("F-Droid"))
        assertTrue(DeviceDiagnosticsFormatter.appVersionSummary(AppDiagnostics("0.19.2", 210, "play")).contains("Play Store"))
    }
}
