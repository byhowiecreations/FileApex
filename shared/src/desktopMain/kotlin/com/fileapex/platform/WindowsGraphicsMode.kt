package com.fileapex.platform

import java.io.File
import java.io.IOException

/**
 * Which Skia renderer the Windows window uses. The choice lives in a plain file in the app data folder because it has to
 * be known before Skiko loads, long before settings or the database open; deleting the file restores the default.
 */
enum class WindowsGraphicsMode(val storageName: String, val renderApi: String) {
    /** OpenGL until Direct3D has proved itself on live resize on real machines. */
    Automatic("automatic", "OPENGL"),
    Direct3D("direct3d", "DIRECT3D"),
    OpenGL("opengl", "OPENGL");

    companion object {
        const val FILE_NAME = "graphics-renderer.txt"

        fun fromText(text: String?): WindowsGraphicsMode =
            entries.firstOrNull { it.storageName.equals(text?.trim(), ignoreCase = true) } ?: Automatic

        fun load(dir: File): WindowsGraphicsMode = try {
            fromText(File(dir, FILE_NAME).takeIf { it.isFile }?.readText())
        } catch (error: IOException) {
            Automatic
        }

        fun save(dir: File, mode: WindowsGraphicsMode) {
            dir.mkdirs()
            File(dir, FILE_NAME).writeText(mode.storageName + "\n")
        }

        /** A renderer named by the launcher or the environment wins over the saved choice. */
        fun effectiveRenderApi(property: String?, mode: WindowsGraphicsMode): String =
            property?.takeIf { it.isNotBlank() } ?: mode.renderApi
    }
}
