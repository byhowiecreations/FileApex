package com.fileapex.cli

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CliDeviceAliasManagerTest {

    @Test
    fun testDefaultSlugGeneration() {
        val device = PairedDeviceEntity(
            deviceId = "dev-12345",
            deviceName = "Moto Razr Fold 2026",
            lastKnownIp = "192.168.1.10",
            port = 49428,
            publicKeyHash = "hash123",
            rootPath = "/"
        )
        val slug = CliDeviceAliasManager.generateDefaultSlug(device)
        assertEquals("moto-razr-fold-2026", slug)
    }

    @Test
    fun testDefaultSlugGenerationFromMakeModel() {
        val device = PairedDeviceEntity(
            deviceId = "dev-67890",
            deviceName = "",
            deviceMake = "Google",
            deviceModel = "Pixel 8 Pro",
            lastKnownIp = "192.168.1.11",
            port = 49428,
            publicKeyHash = "hash456",
            rootPath = "/"
        )
        val slug = CliDeviceAliasManager.generateDefaultSlug(device)
        assertEquals("google-pixel-8-pro", slug)
    }

    @Test
    fun testAliasMappingPersistence() {
        CliDeviceAliasManager.setAlias("dev-test-1", "razr")
        assertEquals("razr", CliDeviceAliasManager.getAlias("dev-test-1"))
        assertEquals("dev-test-1", CliDeviceAliasManager.getDeviceIdBySlug("razr"))
        assertEquals("dev-test-1", CliDeviceAliasManager.getDeviceIdBySlug("RAZR")) // Case-insensitive
    }
}
