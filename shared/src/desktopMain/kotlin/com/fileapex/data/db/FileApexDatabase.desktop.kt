package com.fileapex.data.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.seal.DbFieldKeys
import com.fileapex.data.db.seal.SealingPassCallback
import com.fileapex.data.db.seal.SealingSqliteDriver
import com.fileapex.data.db.MIGRATION_2_3
import com.fileapex.data.db.MIGRATION_3_4
import com.fileapex.data.db.MIGRATION_4_5
import com.fileapex.data.db.MIGRATION_5_6
import com.fileapex.data.db.MIGRATION_6_7
import com.fileapex.data.db.MIGRATION_7_8
import com.fileapex.data.db.MIGRATION_8_9
import com.fileapex.data.db.MIGRATION_9_10
import com.fileapex.data.db.MIGRATION_10_11
import com.fileapex.data.db.MIGRATION_11_12
import com.fileapex.data.bulletin.BulletinBoardMerge
import com.fileapex.platform.DesktopPlatformPaths
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.text.Charsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class RoomDbBuilder {
    actual fun builder(): RoomDatabase.Builder<FileApexDatabase> {
        val dbFile = resolveDesktopDatabaseFile()
        backupDesktopDatabaseIfPresent(dbFile)
        BulletinBoardMerge.backupBeforeMerge(
            dbFile.absolutePath,
            DesktopPlatformPaths.bulletinBoardDatabaseFile().absolutePath
        )
        return Room.databaseBuilder<FileApexDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(SealingSqliteDriver(BundledSQLiteDriver(), DbFieldKeys::sealer))
            .addCallback(SealingPassCallback(DbFieldKeys::sealer))
            .addCallback(MembershipSchemaCallback())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*rosterSafeMigrations())
    }
}

private fun backupDesktopDatabaseIfPresent(dbFile: File) {
    if (!dbFile.isFile || dbFile.length() <= 0L) return
    val backup = File(dbFile.parentFile, "${dbFile.name}.pre-v3-backup")
    if (backup.exists()) return
    runCatching {
        Files.copy(dbFile.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        listOf("-wal", "-shm").forEach { suffix ->
            val sidecar = File(dbFile.parentFile, "${dbFile.name}$suffix")
            if (sidecar.exists()) {
                Files.copy(
                    sidecar.toPath(),
                    File(dbFile.parentFile, "${backup.name}$suffix").toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
        println("FileApexDatabase: saved roster backup ${backup.name}")
    }.onFailure { error ->
        println("FileApexDatabase: roster backup failed - ${error.message}")
    }
}

/**
 * Roster DB path from [DesktopPlatformPaths] (macOS Application Support or Windows LocalAppData).
 * Share Extension on macOS reads the same path with normal file I/O. Migrates macOS legacy
 * locations on first launch when [.roster-resolved] is absent.
 */
internal fun resolveDesktopDatabaseFile(): File {
    val supportDir = DesktopPlatformPaths.applicationSupportDirectory()
    val supportDb = DesktopPlatformPaths.databaseFile()
    val rosterResolvedMarker = DesktopPlatformPaths.rosterResolvedMarkerFile()

    if (!rosterResolvedMarker.exists()) {
        val legacyCandidates = DesktopPlatformPaths.legacyDatabaseMigrationCandidates()

        if (!supportDb.exists()) {
            val source = legacyCandidates.firstOrNull { it.exists() }
            if (source != null) {
                runCatching {
                    copyDatabaseTree(source, supportDb, supportDir)
                }.onFailure { error ->
                    println("FileApexDatabase: DB migration failed - ${error.message}")
                }
            }
        } else if (countPairedDevices(supportDb) == 0) {
            val source = legacyCandidates.firstOrNull { countPairedDevices(it) > 0 }
            if (source != null) {
                runCatching {
                    copyDatabaseTree(source, supportDb, supportDir)
                    println(
                        "FileApexDatabase: restored empty roster from ${source.absolutePath}"
                    )
                }.onFailure { error ->
                    println("FileApexDatabase: roster restore failed - ${error.message}")
                }
            }
        }

        runCatching {
            rosterResolvedMarker.writeText("ok", Charsets.UTF_8)
        }.onFailure { error ->
            println("FileApexDatabase: roster marker write failed - ${error.message}")
        }
    }

    return supportDb
}

/** Devices in a database file: the profiles of a current file, or the paired devices of an older one. */
internal fun countPairedDevices(dbFile: File): Int {
    if (!dbFile.isFile) return 0
    return runCatching {
        val connection = BundledSQLiteDriver().open(dbFile.absolutePath)
        try {
            val table = listOf("member_profiles", "paired_devices").firstOrNull { name ->
                connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name'").use { it.step() }
            } ?: return@runCatching 0
            connection.prepare("SELECT COUNT(*) FROM $table").use { statement ->
                if (statement.step()) statement.getLong(0).toInt() else 0
            }
        } finally {
            connection.close()
        }
    }.getOrElse { 0 }
}

private fun copyDatabaseTree(source: File, targetDb: File, targetDir: File) {
    Files.copy(source.toPath(), targetDb.toPath(), StandardCopyOption.REPLACE_EXISTING)
    listOf("-wal", "-shm").forEach { suffix ->
        val src = File(source.parentFile, "${source.name}$suffix")
        if (src.exists()) {
            Files.copy(
                src.toPath(),
                File(targetDir, "${targetDb.name}$suffix").toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
    println(
        "FileApexDatabase: migrated roster DB to ${targetDir.absolutePath} " +
            "(from ${source.absolutePath})"
    )
}
