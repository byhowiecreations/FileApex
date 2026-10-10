package com.fileapex.platform

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual fun renameLocalEntry(absolutePath: String, newName: String): String {
    val trimmed = newName.trim()
    if (trimmed.isEmpty() || trimmed.contains('/') || trimmed.contains('\\')) {
        error("invalid name")
    }
    val source = File(absolutePath)
    val dest = File(source.parentFile, trimmed)
    if (dest.exists()) error("already exists")
    if (!source.renameTo(dest)) error("rename failed")
    return dest.absolutePath
}

actual fun zipLocalEntry(absolutePath: String): String {
    val source = File(absolutePath)
    val zipFile = File(source.parentFile, source.name + ".zip")
    ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
        if (source.isDirectory) {
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = source.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        } else {
            zip.putNextEntry(ZipEntry(source.name))
            source.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }
    return zipFile.absolutePath
}

actual fun unzipLocalEntry(absolutePath: String): String {
    val zipFile = File(absolutePath)
    val destDir = File(zipFile.parentFile, zipFile.nameWithoutExtension)
    destDir.mkdirs()
    ZipInputStream(FileInputStream(zipFile)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val out = File(destDir, entry.name)
            val canonicalDest = destDir.canonicalPath
            val canonicalOut = out.canonicalPath
            if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                error("zip entry escaped destination")
            }
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { output -> zip.copyTo(output) }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return destDir.absolutePath
}

actual fun moveLocalEntryInto(absolutePath: String, destinationDirectory: String) {
    val source = File(absolutePath)
    val destDir = File(destinationDirectory)
    if (!source.exists()) return
    destDir.mkdirs()
    val dest = File(destDir, source.name)
    if (source.canonicalPath == dest.canonicalPath) return
    if (source.isDirectory && dest.canonicalPath.startsWith(source.canonicalPath + File.separator)) {
        error("cannot move into itself")
    }
    if (dest.exists()) error("already exists")
    if (!source.renameTo(dest)) {
        source.copyRecursively(dest, overwrite = false)
        if (treeSize(source) != treeSize(dest)) {
            dest.deleteRecursively()
            error("move failed")
        }
        if (!source.deleteRecursively()) error("move failed")
    }
}

/** File count and total bytes under [root], for checking a copy before its source is removed. */
private fun treeSize(root: File): Pair<Int, Long> {
    val files = root.walkTopDown().filter { it.isFile }.toList()
    return files.size to files.sumOf { it.length() }
}

actual fun copyLocalEntryInto(absolutePath: String, destinationDirectory: String) {
    val source = File(absolutePath)
    val destDir = File(destinationDirectory)
    destDir.mkdirs()
    val dest = File(destDir, source.name)
    if (source.isDirectory) {
        source.copyRecursively(dest, overwrite = false)
    } else {
        source.copyTo(dest, overwrite = false)
    }
}
