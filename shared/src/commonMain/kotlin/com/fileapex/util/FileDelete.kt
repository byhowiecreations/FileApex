package com.fileapex.util

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Removes a file that is already of no use (a part file, a ledger, a stale key marker). A missing file is fine. A file
 * that cannot be removed is logged by [what] and the exception class only, never the path.
 */
fun File.deleteLogged(what: String) {
    try {
        Files.deleteIfExists(toPath())
    } catch (error: IOException) {
        println("$what failed - ${error::class.simpleName}")
    }
}
