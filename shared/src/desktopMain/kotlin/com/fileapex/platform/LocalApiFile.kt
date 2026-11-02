package com.fileapex.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.io.IOException

/** Writes `local-api.json` for the macOS extensions: written new, owner-only, then moved into place. */
object LocalApiFile {
    fun publish(payload: String) = publish(DesktopPlatformPaths.localApiFile(), payload)

    internal fun publish(file: File, payload: String) {
        val dir = file.absoluteFile.parentFile.also { it.mkdirs() }
        if (runCatching { file.isFile && file.readText() == payload && ownerOnly(file) }.getOrDefault(false)) return
        val ownerOnly = privateAttribute(dir)
        val temp = Files.createTempFile(dir.toPath(), "${file.name}.", ".tmp", ownerOnly)
        try {
            Files.writeString(temp, payload)
            Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun privateAttribute(dir: File): FileAttribute<*> {
        val store = Files.getFileStore(dir.toPath())
        if (store.supportsFileAttributeView("posix")) return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        if (!store.supportsFileAttributeView("acl")) throw IOException("Private local files are unsupported on this filesystem")
        val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(dir.toPath()))
            .setPermissions(java.util.EnumSet.allOf(AclEntryPermission::class.java)).build()
        return object : FileAttribute<List<AclEntry>> {
            override fun name() = "acl:acl"
            override fun value() = listOf(entry)
        }
    }

    internal fun ownerOnly(file: File): Boolean {
        if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix"))
            return Files.getPosixFilePermissions(file.toPath()) == PosixFilePermissions.fromString("rw-------")
        val acl = Files.getFileAttributeView(file.toPath(), AclFileAttributeView::class.java) ?: return false
        return acl.acl.all { it.type() == AclEntryType.ALLOW && it.principal() == acl.owner } && acl.acl.isNotEmpty()
    }
}

/**
 * The old share handoff staged full copies of the files you shared and wrote job files with paths and note text. Nothing
 * writes them any more, so any that an older version left are removed at start.
 */
object LegacyHandoffCleanup {
    private val folders = listOf("send-jobs", "send-staging", "bulletin-jobs")
    private val files = listOf("ready-device-ids.json")

    fun run(supportDirectory: File) {
        folders.forEach { runCatching { File(supportDirectory, it).deleteRecursively() } }
        files.forEach { runCatching { File(supportDirectory, it).delete() } }
    }

    fun run() = run(DesktopPlatformPaths.applicationSupportDirectory())
}
