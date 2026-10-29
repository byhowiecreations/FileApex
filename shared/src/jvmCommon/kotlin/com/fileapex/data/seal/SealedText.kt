package com.fileapex.data.seal

import com.fileapex.data.db.seal.DbFieldKeys
import com.fileapex.data.db.seal.FieldSealer
import java.io.File

/**
 * Text the app keeps on disk, sealed with the database field key (AES-256-GCM, the key lives in the platform vault).
 * [purpose] is bound in as associated data, so a value cannot be moved to another store. Without a key nothing is
 * written in clear text and a sealed value reads as empty; text left in clear by an older version is returned as is and
 * sealed in place the first time it is read.
 */
object SealedText {
    private const val TABLE = "file"

    /** The key lookup; tests replace it. */
    @Volatile
    internal var sealerProvider: () -> FieldSealer? = { DbFieldKeys.sealer() }

    fun seal(purpose: String, plain: String): String? = sealerProvider()?.seal(TABLE, purpose, plain)

    /** The plain text, or null when [stored] is sealed and cannot be opened (no key, wrong purpose, altered). */
    fun open(purpose: String, stored: String): String? {
        if (!stored.startsWith(FieldSealer.PREFIX)) return stored
        return sealerProvider()?.open(TABLE, purpose, stored)
    }

    /** True when the text was sealed and written; false (file untouched) when no key is available yet. */
    fun writeFile(file: File, purpose: String, plain: String): Boolean {
        val sealed = if (plain.isEmpty()) "" else seal(purpose, plain) ?: return false
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(sealed)
        if (!temp.renameTo(file)) {
            file.writeText(sealed)
            temp.delete()
        }
        return true
    }

    /** The file's text, empty when it is missing or locked. An older plain file is sealed in place. */
    fun readFile(file: File, purpose: String): String {
        if (!file.isFile) return ""
        val stored = runCatching { file.readText() }.getOrDefault("")
        if (stored.isEmpty()) return ""
        val plain = open(purpose, stored) ?: return ""
        if (!stored.startsWith(FieldSealer.PREFIX)) runCatching { writeFile(file, purpose, plain) }
        return plain
    }
}
