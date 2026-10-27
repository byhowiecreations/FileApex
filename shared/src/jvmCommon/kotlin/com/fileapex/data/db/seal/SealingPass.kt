package com.fileapex.data.db.seal

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Seals rows written before sealing existed, or while no key was available. Safe to run on every start. */
object SealingPass {
    /** Number of rows changed. [connection] is the driver's own or a [SealingConnection]; either way each row ends sealed. */
    fun run(connection: SQLiteConnection, sealer: FieldSealer, columns: Map<String, Set<String>> = SealedColumns.STAGE_ONE): Int {
        val ownTransaction = !connection.inTransaction()
        if (ownTransaction) connection.execSQL("BEGIN IMMEDIATE")
        var changed = 0
        try {
            val sealsItself = connection is SealingConnection
            for ((table, names) in columns) changed += sealTable(connection, sealer, table, names.sorted(), sealsItself)
            if (ownTransaction) connection.execSQL("COMMIT")
        } catch (e: Throwable) {
            if (ownTransaction) runCatching { connection.execSQL("ROLLBACK") }
            throw e
        }
        return changed
    }

    private fun sealTable(connection: SQLiteConnection, sealer: FieldSealer, table: String, names: List<String>, sealsItself: Boolean): Int {
        val unsealed = names.joinToString(" OR ") { "(`$it` IS NOT NULL AND `$it` != '' AND substr(`$it`, 1, ${FieldSealer.PREFIX.length}) != '${FieldSealer.PREFIX}')" }
        val rows = mutableListOf<Pair<Long, List<String?>>>()
        connection.prepare("SELECT rowid, ${names.joinToString(", ") { "`$it`" }} FROM `$table` WHERE $unsealed").use { s ->
            while (s.step()) rows += s.getLong(0) to names.indices.map { i -> if (s.isNull(i + 1)) null else s.getText(i + 1) }
        }
        for ((rowid, values) in rows) {
            connection.prepare("UPDATE `$table` SET ${names.joinToString(", ") { "`$it` = ?" }} WHERE rowid = ?").use { u ->
                names.forEachIndexed { i, name ->
                    val plain = values[i]
                    if (plain == null) u.bindNull(i + 1) else u.bindText(i + 1, if (sealsItself) plain else sealer.seal(table, name, plain))
                }
                u.bindLong(names.size + 1, rowid)
                u.step()
            }
        }
        if (rows.isNotEmpty()) {
            val left = connection.prepare("SELECT COUNT(*) FROM `$table` WHERE $unsealed").use { it.step(); it.getLong(0) }
            check(left == 0L) { "$left rows of $table are still plain after sealing" }
        }
        return rows.size
    }
}

/** Runs [SealingPass] each time the database opens, when the key is available. */
class SealingPassCallback(private val sealer: () -> FieldSealer?) : RoomDatabase.Callback() {
    override suspend fun onOpen(connection: SQLiteConnection) {
        val key = sealer() ?: return
        val changed = SealingPass.run(connection, key)
        if (changed > 0) println("FileApexDatabase: sealed $changed rows")
    }
}
