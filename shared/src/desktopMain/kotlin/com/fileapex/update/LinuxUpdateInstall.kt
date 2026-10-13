package com.fileapex.update

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

internal enum class LinuxInstallKind { AppImage, Deb, Rpm, Other }

internal object LinuxInstall {
    val kind: LinuxInstallKind by lazy { detect() }

    fun appImageFile(): File? =
        System.getenv("APPIMAGE")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }

    /** An AppImage in a folder the user can write replaces itself. */
    fun replacesItself(): Boolean = kind == LinuxInstallKind.AppImage && appImageFile()?.absoluteFile?.parentFile?.canWrite() == true

    private fun detect(): LinuxInstallKind {
        if (appImageFile() != null) return LinuxInstallKind.AppImage
        if (File("/var/lib/dpkg/info/fileapex.list").isFile) return LinuxInstallKind.Deb
        if (rpmKnowsFileApex()) return LinuxInstallKind.Rpm
        val family = (osRelease("ID_LIKE") + " " + osRelease("ID")).lowercase()
        return when {
            "debian" in family || "ubuntu" in family -> LinuxInstallKind.Deb
            "fedora" in family || "rhel" in family || "suse" in family -> LinuxInstallKind.Rpm
            else -> LinuxInstallKind.Other
        }
    }

    private fun rpmKnowsFileApex(): Boolean = runCatching {
        val process = ProcessBuilder("rpm", "-q", "fileapex")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun osRelease(key: String): String =
        runCatching { File("/etc/os-release").readLines() }.getOrDefault(emptyList())
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.trim()?.trim('"', '\'').orEmpty()
}

/** The release file for this install type and CPU, or null when the release does not carry one. */
internal fun linuxAssetFor(assets: List<GitHubReleaseAsset>, kind: LinuxInstallKind, arch: String): GitHubReleaseAsset? {
    val arm = arch == "aarch64" || arch == "arm64"
    val (suffix, cpu) = when (kind) {
        LinuxInstallKind.AppImage -> ".appimage" to if (arm) "aarch64" else "x86_64"
        LinuxInstallKind.Deb -> ".deb" to if (arm) "arm64" else "amd64"
        LinuxInstallKind.Rpm -> ".rpm" to if (arm) "aarch64" else "x86_64"
        LinuxInstallKind.Other -> return null
    }
    return assets.firstOrNull { it.name.lowercase().let { name -> name.endsWith(suffix) && "-$cpu" in name } }
}

/** Copies [downloaded] beside [current] as [newName], swaps it in atomically and removes the old file. */
internal fun replaceAppImage(current: File, downloaded: File, newName: String): File {
    require(File(newName).name == newName) { "Unsafe update file name" }
    val dir = current.absoluteFile.parentFile
    val target = File(dir, newName)
    val staged = File(dir, "$newName.update")
    Files.copy(downloaded.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
    check(staged.length() == downloaded.length()) { "Copied update is ${staged.length()} bytes, expected ${downloaded.length()}" }
    staged.setExecutable(true, false)
    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    if (target.canonicalPath != current.canonicalPath) Files.deleteIfExists(current.toPath())
    return target
}

/** Moves the downloaded update into [folder] under [name] so the user can run or install it from there. */
internal fun saveUpdateToFolder(downloaded: File, folder: File, name: String): File {
    require(File(name).name == name) { "Unsafe update file name" }
    folder.mkdirs()
    val target = File(folder, name)
    Files.copy(downloaded.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    check(target.length() == downloaded.length()) { "Saved update is ${target.length()} bytes, expected ${downloaded.length()}" }
    downloaded.delete()
    return target
}
