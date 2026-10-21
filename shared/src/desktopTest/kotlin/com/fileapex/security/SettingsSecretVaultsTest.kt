package com.fileapex.security

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSecretVaultsTest {
    private fun tempDir() = Files.createTempDirectory("fileapex-vault").toFile().also { it.deleteOnExit() }
    private val secret = "clipboard-private-key".toByteArray()

    @Test
    fun theFileKeyVaultSealsAndOpens() {
        val vault = FileKeyVault(tempDir())
        val sealed = vault.seal(secret)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("clipboard-private-key"))
        assertArrayEquals(secret, vault.open(sealed))
    }

    @Test
    fun theKeySurvivesARestartAndIsOwnerOnly() {
        val dir = tempDir()
        val sealed = FileKeyVault(dir).seal(secret)
        assertArrayEquals(secret, FileKeyVault(dir).open(sealed))
        val perms = Files.getPosixFilePermissions(File(dir, "settings-wrap.key").toPath())
        assertEquals(setOf(PermissionSet.OWNER_READ, PermissionSet.OWNER_WRITE), perms)
    }

    @Test
    fun anotherDirectoryHasAnotherKey() {
        val sealed = FileKeyVault(tempDir()).seal(secret)
        val opened = runCatching { FileKeyVault(tempDir()).open(sealed) }
        assertTrue(opened.isFailure)
    }

    @Test
    fun linuxWithoutAKeyringFallsBackToTheFileKey() {
        val vault = LinuxSecretVault(FileKeyVault(tempDir()), CommandRunner { _, _ -> error("secret-tool: not found") })
        assertArrayEquals(secret, vault.open(vault.seal(secret)))
    }

    private val oldKeyringKey = ByteArray(32) { (it + 7).toByte() }
    private val oldKeyringHex = oldKeyringKey.joinToString("") { "%02x".format(it) }

    /** What 0.19.1 wrote when it sealed with the keyring key. */
    private fun keyringBlob(): ByteArray = byteArrayOf(1) + SecretAes.seal(oldKeyringKey, secret)

    @Test
    fun newSecretsAreSealedWithTheFileKeyAndNeverTouchTheKeyring() {
        val seen = mutableListOf<List<String>>()
        val dir = tempDir()
        val vault = LinuxSecretVault(FileKeyVault(dir), CommandRunner { args, _ -> seen += args; 1 to "" })
        val sealed = vault.seal(secret)
        assertEquals(2, sealed[0].toInt())
        assertTrue(File(dir, "settings-wrap.key").exists())
        assertTrue(seen.isEmpty())
        assertFalse(vault.needsReseal(sealed))
    }

    @Test
    fun aValueSealedWithTheKeyringByAnOlderVersionStillOpensAndIsMarkedForResealing() {
        val vault = LinuxSecretVault(
            FileKeyVault(tempDir()),
            CommandRunner { args, _ -> if (args[1] == "lookup") 0 to oldKeyringHex else 1 to "" }
        )
        val blob = keyringBlob()
        assertArrayEquals(secret, vault.open(blob))
        assertTrue(vault.needsReseal(blob))
    }

    @Test
    fun aKeyringValueIsUnavailableNotLostWhenTheKeyringCannotBeRead() {
        val stores = mutableListOf<List<String>>()
        val vault = LinuxSecretVault(
            FileKeyVault(tempDir()),
            CommandRunner { args, _ -> stores += args; 1 to "" }
        )
        val failure = runCatching { vault.open(keyringBlob()) }.exceptionOrNull()
        assertTrue(failure is com.fileapex.data.settings.SecretUnavailableException)
        // The old key is never replaced by a new one while it cannot be read.
        assertTrue(stores.none { it[1] == "store" })
    }

    private object PermissionSet {
        val OWNER_READ = PosixFilePermission.OWNER_READ
        val OWNER_WRITE = PosixFilePermission.OWNER_WRITE
    }
}
