package com.fileapex.cli

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.seal.SealedText
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CliDeviceAliasManagerTest {
    private lateinit var dir: File
    private var sealer: FieldSealer? = null

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cli-aliases").toFile()
        sealer = FieldSealer(ByteArray(32) { it.toByte() })
        SealedText.sealerProvider = { sealer }
        CliDeviceAliasManager.directory = { dir }
    }

    @After
    fun tearDown() {
        SealedText.sealerProvider = { null }
        CliDeviceAliasManager.directory = null
        dir.deleteRecursively()
    }

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
        assertTrue(CliDeviceAliasManager.setAlias("dev-test-1", "razr"))
        assertEquals("razr", CliDeviceAliasManager.getAlias("dev-test-1"))
        assertTrue(CliDeviceAliasManager.setAlias("dev-test-1", " RAZR "))
        assertEquals("razr", CliDeviceAliasManager.getAlias("dev-test-1"))
    }

    @Test
    fun theAliasFileOnDiskIsSealed() {
        CliDeviceAliasManager.setAlias("dev-secret-id", "kitchen-tablet")
        val stored = File(dir, "cli_device_aliases.json").readText()
        assertFalse(stored.contains("kitchen-tablet"))
        assertFalse(stored.contains("dev-secret-id"))
        assertEquals("kitchen-tablet", CliDeviceAliasManager.getAlias("dev-secret-id"))
    }

    @Test
    fun aPlainFileFromAnOlderVersionIsReadAndSealedInPlace() {
        val file = File(dir, "cli_device_aliases.json")
        file.writeText("""{"slugToDeviceId":{"old":"dev-old"},"deviceIdToSlug":{"dev-old":"old"}}""")
        assertEquals("old", CliDeviceAliasManager.getAlias("dev-old"))
        assertFalse(file.readText().contains("dev-old"))
        assertEquals("old", CliDeviceAliasManager.getAlias("dev-old"))
    }

    @Test
    fun aLockedKeyWritesNothingAndSaysSo() {
        sealer = null
        assertFalse(CliDeviceAliasManager.setAlias("dev-1", "razr"))
        assertFalse(File(dir, "cli_device_aliases.json").exists())
    }
}
