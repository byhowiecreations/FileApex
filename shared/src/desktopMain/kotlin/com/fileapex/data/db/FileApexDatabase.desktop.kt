package com.fileapex.data.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.MIGRATION_2_3
import com.fileapex.data.db.MIGRATION_3_4
import com.fileapex.data.db.MIGRATION_4_5
import com.fileapex.platform.DesktopPlatformPaths
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class RoomDbBuilder {
    actual fun builder(): RoomDatabase.Builder<FileApexDatabase> {
        val dbFile = resolveDesktopDatabaseFile()
        backupDesktopDatabaseIfPresent(dbFile)
        return Room.databaseBuilder<FileApexDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
        println("FileApexDatabase: saved roster backup at ${backup.absolutePath}")
    }.onFailure { error ->
        println("FileApexDatabase: roster backup failed — ${error.message}")
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
                    println("FileApexDatabase: DB migration failed — ${error.message}")
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
                    println("FileApexDatabase: roster restore failed — ${error.message}")
                }
            }
        }

        runCatching {
            rosterResolvedMarker.writeText("ok")
        }.onFailure { error ->
            println("FileApexDatabase: roster marker write failed — ${error.message}")
        }
    }

    return supportDb
}

private fun countPairedDevices(dbFile: File): Int {
    if (!dbFile.isFile) return 0
    return runCatching {
        val driver = BundledSQLiteDriver()
        val connection = driver.open(dbFile.absolutePath)
        try {
            connection.prepare("SELECT COUNT(*) FROM paired_devices").use { statement ->
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
