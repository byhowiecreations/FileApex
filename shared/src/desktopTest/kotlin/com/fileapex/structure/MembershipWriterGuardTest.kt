package com.fileapex.structure

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `members` is the chain's projection and has one writer; `member_local` and `legacy_removed` are the local inputs of that
 * projection and have the pin store, the pairing and removal code as their only writers. A new writer anywhere else
 * fails here, not in the field.
 */
class MembershipWriterGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val main = File(root, "shared/src").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && listOf("/commonMain/", "/androidMain/", "/desktopMain/", "/jvmCommon/").any { dir -> dir in it.path } }.toList()

    private fun writers(table: String): Set<String> {
        val write = Regex("""(INSERT\s+(OR\s+\w+\s+)?INTO|UPDATE|DELETE\s+FROM|REPLACE\s+INTO)\s+`?$table`?\b""", RegexOption.IGNORE_CASE)
        return main.filter { write.containsMatchIn(it.readText()) }.map { it.name }.toSortedSet()
    }

    @Test
    fun onlyTheProjectionAndTheMigrationsWriteTheMembersTable() {
        assertEquals(setOf("FileApexMigrations.kt", "MembershipMigrations.kt", "MembershipTables.kt"), writers("members"))
    }

    @Test
    fun theLocalFactsAreWrittenOnlyByThePinStoreThePairingAndRemovalCodeAndTheProjectionsOwnHousekeeping() {
        assertEquals(setOf("FileApexDatabase.kt", "MembershipMigrations.kt", "MembershipTables.kt", "TlsPinTables.kt"), writers("member_local") + writers("legacy_removed"))
    }

    @Test
    fun noCodeReadsAPinFromTheMembersTable() {
        val bad = Regex("""members(\.|\s+AS\s+\w+\.)?tlsPin|m\.tlsPin""")
        val offenders = main.filter { it.name != "MembershipMigrations.kt" && it.name != "FileApexMigrations.kt" && bad.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }
}
