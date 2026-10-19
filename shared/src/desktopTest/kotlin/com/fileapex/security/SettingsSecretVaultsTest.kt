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

    @Test
    fun linuxUsesTheKeyringWhenThereIsOne() {
        val stored = mutableMapOf<String, String>()
        val runner = CommandRunner { args, stdin ->
            when (args[1]) {
                "store" -> { stored["key"] = stdin!!; 0 to "" }
                "lookup" -> if (stored.containsKey("key")) 0 to stored.getValue("key") else 1 to ""
                else -> 1 to ""
            }
        }
        val dir = tempDir()
        val vault = LinuxSecretVault(FileKeyVault(dir), runner)
        val sealed = vault.seal(secret)
        assertEquals(1, sealed[0].toInt())
        assertArrayEquals(secret, vault.open(sealed))
        assertFalse(File(dir, "settings-wrap.key").exists())
        // A later run with the same keyring opens the same value.
        assertArrayEquals(secret, LinuxSecretVault(FileKeyVault(dir), runner).open(sealed))
    }

    @Test
    fun aKeyringValueIsNotOpenedWhenTheKeyringIsGone() {
        val stored = mutableMapOf<String, String>()
        val withKeyring = CommandRunner { args, stdin ->
            when (args[1]) {
                "store" -> { stored["key"] = stdin!!; 0 to "" }
                "lookup" -> if (stored.containsKey("key")) 0 to stored.getValue("key") else 1 to ""
                else -> 1 to ""
            }
        }
        val dir = tempDir()
        val sealed = LinuxSecretVault(FileKeyVault(dir), withKeyring).seal(secret)
        val without = LinuxSecretVault(FileKeyVault(dir), CommandRunner { _, _ -> error("not found") })
        assertTrue(runCatching { without.open(sealed) }.isFailure)
    }

    @Test
    fun theKeyIsNeverPassedOnTheCommandLine() {
        val seen = mutableListOf<List<String>>()
        val stored = mutableMapOf<String, String>()
        val runner = CommandRunner { args, stdin ->
            seen += args
            when (args[1]) {
                "store" -> { stored["key"] = stdin!!; 0 to "" }
                "lookup" -> if (stored.containsKey("key")) 0 to stored.getValue("key") else 1 to ""
                else -> 1 to ""
            }
        }
        LinuxSecretVault(FileKeyVault(tempDir()), runner).seal(secret)
        val secretHex = stored.getValue("key")
        assertTrue(seen.none { args -> args.any { it.contains(secretHex) } })
        assertNotEquals("", secretHex)
    }

    private object PermissionSet {
        val OWNER_READ = PosixFilePermission.OWNER_READ
        val OWNER_WRITE = PosixFilePermission.OWNER_WRITE
    }
}
