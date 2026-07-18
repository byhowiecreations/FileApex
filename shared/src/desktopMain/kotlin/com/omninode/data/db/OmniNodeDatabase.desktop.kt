package com.omninode.data.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.omninode.platform.MACOS_APP_GROUP_ID
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
 * Prefer the App Group container so Finder Sync / Share Extension can read the
 * same `omninode.db` roster. Falls back to `~/.omninode/` only if the group
 * container cannot be created (should not happen on normal macOS installs).
 */
internal fun resolveDesktopDatabaseFile(): File {
    val home = System.getProperty("user.home") ?: "."
    val groupRoot = File(home, "Library/Group Containers/$MACOS_APP_GROUP_ID")
    val dbDir = File(groupRoot, "Database")
    val legacyDir = File(home, ".omninode")
    val legacyDb = File(legacyDir, "omninode.db")
    val groupDb = File(dbDir, "omninode.db")

    if (!dbDir.exists()) {
        dbDir.mkdirs()
    }
    // Marker so Container Manager recognizes a populated group container.
    File(groupRoot, ".com.apple.containermanagerd.metadata.plist").let { marker ->
        if (!marker.exists() && groupRoot.exists()) {
            runCatching {
                marker.writeText(
                    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict></dict></plist>
"""
                )
            }
        }
    }

    if (!groupDb.exists() && legacyDb.exists()) {
        runCatching {
            Files.copy(
                legacyDb.toPath(),
                groupDb.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            // Also copy Room WAL/SHM if present so we don't lose a hot DB.
            listOf("-wal", "-shm").forEach { suffix ->
                val src = File(legacyDir, "omninode.db$suffix")
                if (src.exists()) {
                    Files.copy(
                        src.toPath(),
                        File(dbDir, "omninode.db$suffix").toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
            println("OmniNodeDatabase: migrated roster DB to App Group container")
        }.onFailure { error ->
            println("OmniNodeDatabase: App Group migration failed — ${error.message}")
        }
    }

    if (groupDb.parentFile?.exists() == true) {
        return groupDb
    }
    if (!legacyDir.exists()) {
        legacyDir.mkdirs()
    }
    return legacyDb
}
