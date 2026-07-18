package com.omninode.data.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.omninode.platform.MacOsSharedPaths
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class RoomDbBuilder {
    actual fun builder(): RoomDatabase.Builder<OmniNodeDatabase> {
        val dbFile = resolveDesktopDatabaseFile()
        return Room.databaseBuilder<OmniNodeDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .fallbackToDestructiveMigration(dropAllTables = true)
    }
}

/**
 * Roster DB lives at:
 * `~/Library/Application Support/com.omninode/omninode.db`
 *
 * Finder Sync and Share Extension read the same path with normal file I/O
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
                Files.copy(
                    source.toPath(),
                    supportDb.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                listOf("-wal", "-shm").forEach { suffix ->
                    val src = File(source.parentFile, "omninode.db$suffix")
                    if (src.exists()) {
                        Files.copy(
                            src.toPath(),
                            File(supportDir, "omninode.db$suffix").toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                }
                println(
                    "OmniNodeDatabase: migrated roster DB to Application Support " +
                        "(from ${source.absolutePath})"
                )
            }.onFailure { error ->
                println("OmniNodeDatabase: DB migration failed — ${error.message}")
            }
        }
    }

    return supportDb
}
