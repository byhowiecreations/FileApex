package com.omninode.data.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.omninode.data.db.MIGRATION_2_3
import com.omninode.data.db.MIGRATION_3_4
import com.omninode.platform.MacOsSharedPaths
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class RoomDbBuilder {
    actual fun builder(): RoomDatabase.Builder<OmniNodeDatabase> {
        val dbFile = resolveDesktopDatabaseFile()
        backupDesktopDatabaseIfPresent(dbFile)
        return Room.databaseBuilder<OmniNodeDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
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
        println("OmniNodeDatabase: saved roster backup at ${backup.absolutePath}")
    }.onFailure { error ->
        println("OmniNodeDatabase: roster backup failed — ${error.message}")
    }
}

/**
 * Roster DB lives at:
 * `~/Library/Application Support/com.omninode/omninode.db`
 *
 * Share Extension reads the same path with normal file I/O
 * (no App Group / sandbox). Migrates from older locations when present.
 */
internal fun resolveDesktopDatabaseFile(): File {
    val home = System.getProperty("user.home") ?: "."
    val supportDir = File(home, "Library/Application Support/${MacOsSharedPaths.BUNDLE_ID}")
    val supportDb = File(supportDir, MacOsSharedPaths.DATABASE_FILE_NAME)

    if (!supportDir.exists()) {
        supportDir.mkdirs()
    }

    val legacyCandidates = listOf(
        File(home, ".omninode/omninode.db"),
        File(
            home,
            "Library/Group Containers/group.com.omninode/Database/omninode.db"
        )
    )

    if (!supportDb.exists()) {
        val source = legacyCandidates.firstOrNull { it.exists() }
        if (source != null) {
            runCatching {
                copyDatabaseTree(source, supportDb, supportDir)
            }.onFailure { error ->
                println("OmniNodeDatabase: DB migration failed — ${error.message}")
            }
        }
    } else if (countPairedDevices(supportDb) == 0) {
        val source = legacyCandidates.firstOrNull { countPairedDevices(it) > 0 }
        if (source != null) {
            runCatching {
                copyDatabaseTree(source, supportDb, supportDir)
                println(
                    "OmniNodeDatabase: restored empty roster from ${source.absolutePath}"
                )
            }.onFailure { error ->
                println("OmniNodeDatabase: roster restore failed — ${error.message}")
            }
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
        "OmniNodeDatabase: migrated roster DB to Application Support " +
            "(from ${source.absolutePath})"
    )
}
