package com.fileapex.data.db.seal

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.util.concurrent.ConcurrentHashMap

/** The table.column pairs that are sealed on disk. Nothing queries by one of these columns (see SealedColumnsGuardTest). */
object SealedColumns {
    val STAGE_ONE: Map<String, Set<String>> = mapOf(
        "paired_devices" to setOf("deviceName", "lastKnownIp", "rootPath", "deviceMake", "deviceModel", "tailnetHostname", "tailnetIpv4"),
        "messages" to setOf("senderName", "content"),
        "checklist_items" to setOf("text"),
        "pending_transfers" to setOf("sourceJson", "displayLabel", "lastError"),
        "peer_candidates" to setOf("deviceJson"),
        "removed_devices" to setOf("lastKnownIp"),
        "friend_links" to setOf("label", "groupName", "folderName"),
        "friend_incoming" to setOf("fileName", "quarantinePath")
    )
}

/** A sensitive value was written while the field key is not available. Nothing is written in clear text instead. */
class SealedStoreLockedException(message: String) : IllegalStateException(message)

/**
 * Wraps the platform driver so text bound to a sealed column is sealed on its way in and opened on its way out. Room,
 * the DAOs and the entities see plain text. With no key ([sealer] returns null) the store is locked: a write to a sealed
 * column fails with [SealedStoreLockedException] (nothing is ever written in clear text) and sealed values read as empty
 * text. Rows written before sealing existed are sealed at the next open with the key (see [SealingPass]).
 */
class SealingSqliteDriver(
    private val delegate: SQLiteDriver,
    private val sealer: () -> FieldSealer?,
    private val columns: Map<String, Set<String>> = SealedColumns.STAGE_ONE
) : SQLiteDriver by delegate {
    private val plans = ConcurrentHashMap<String, StatementPlan>()

    override fun open(fileName: String): SQLiteConnection = SealingConnection(delegate.open(fileName), this)

    internal fun plan(sql: String): StatementPlan = plans.getOrPut(sql) { StatementPlan.of(sql, columns) }

    internal fun currentSealer(): FieldSealer? = sealer()
}

class SealingConnection internal constructor(val raw: SQLiteConnection, private val driver: SealingSqliteDriver) : SQLiteConnection by raw {
    override fun prepare(sql: String): SQLiteStatement {
        val plan = driver.plan(sql)
        val statement = raw.prepare(sql)
        return if (plan.table == null) statement else SealingStatement(statement, plan, driver)
    }
}

private class SealingStatement(
    private val delegate: SQLiteStatement,
    private val plan: StatementPlan,
    private val driver: SealingSqliteDriver
) : SQLiteStatement by delegate {
    private var resultColumns: Array<String?>? = null

    override fun bindText(index: Int, value: String) {
        val column = plan.boundColumns[index]
        if (column == null || value.isEmpty()) return delegate.bindText(index, value)
        val sealer = driver.currentSealer() ?: throw SealedStoreLockedException("the database key is not available, so ${plan.table}.$column was not written")
        delegate.bindText(index, sealer.seal(plan.table!!, column, value))
    }

    override fun getText(index: Int): String {
        val stored = delegate.getText(index)
        val column = resultColumn(index) ?: return stored
        val sealer = driver.currentSealer()
        if (sealer == null) return if (stored.startsWith(FieldSealer.PREFIX)) "" else stored
        return sealer.open(plan.table!!, column, stored) ?: ""
    }

    private fun resultColumn(index: Int): String? {
        val cached = resultColumns ?: arrayOfNulls<String>(delegate.getColumnCount()).also { names ->
            for (i in names.indices) names[i] = delegate.getColumnName(i).takeIf { it in plan.sealedNames }
            resultColumns = names
        }
        return cached.getOrNull(index)
    }
}

/** Which bound parameter of a statement is a sealed column, worked out once per SQL text. */
internal class StatementPlan(val table: String?, val boundColumns: Map<Int, String>, val sealedNames: Set<String>) {
    companion object {
        private val NONE = StatementPlan(null, emptyMap(), emptySet())
        private val TABLE = Regex("""(?i)\b(?:INTO|UPDATE|FROM)\s+[`"\[]?([A-Za-z_][A-Za-z0-9_]*)[`"\]]?""")
        private val INSERT = Regex("""(?is)^\s*INSERT\s+(?:OR\s+\w+\s+)?INTO\s+[`"\[]?(\w+)[`"\]]?\s*\((.*?)\)\s*VALUES\s*\((.*)\)\s*;?\s*$""")
        private val UPDATE = Regex("""(?is)^\s*UPDATE\s+(?:OR\s+\w+\s+)?[`"\[]?(\w+)[`"\]]?\s+SET\s+(.*?)(?:\s+WHERE\s+(.*))?;?\s*$""")

        fun of(sql: String, columns: Map<String, Set<String>>): StatementPlan {
            val table = TABLE.find(sql)?.groupValues?.get(1)?.takeIf { it in columns } ?: return NONE
            val sealed = columns.getValue(table)
            if (Regex("""\?\d""").containsMatchIn(sql) && sealed.any { sql.contains(it) }) refuse(sql)
            val trimmed = sql.trimStart()
            return when {
                trimmed.startsWith("INSERT", ignoreCase = true) -> insert(sql, table, sealed)
                trimmed.startsWith("UPDATE", ignoreCase = true) -> update(sql, table, sealed)
                else -> StatementPlan(table, emptyMap(), sealed)
            }
        }

        private fun insert(sql: String, table: String, sealed: Set<String>): StatementPlan {
            val m = INSERT.find(sql) ?: refuse(sql)
            val names = m.groupValues[2].split(',').map { it.trim().trim('`', '"', '[', ']') }
            val values = splitTopLevel(m.groupValues[3])
            if (names.size != values.size) refuse(sql)
            val bound = HashMap<Int, String>()
            var index = 0
            for ((i, value) in values.withIndex()) {
                val v = value.trim()
                if (v == "?") { index++; if (names[i] in sealed) bound[index] = names[i] }
                else if (names[i] in sealed) refuse(sql)
                else index += v.count { it == '?' }
            }
            return StatementPlan(table, bound, sealed)
        }

        private fun update(sql: String, table: String, sealed: Set<String>): StatementPlan {
            val m = UPDATE.find(sql) ?: refuse(sql)
            val bound = HashMap<Int, String>()
            var index = 0
            for (assignment in splitTopLevel(m.groupValues[2])) {
                val eq = assignment.indexOf('=')
                if (eq < 0) refuse(sql)
                val name = assignment.substring(0, eq).trim().trim('`', '"', '[', ']')
                val expr = assignment.substring(eq + 1).trim()
                if (expr == "?") { index++; if (name in sealed) bound[index] = name }
                else if (name in sealed) refuse(sql)
                else index += expr.count { it == '?' }
            }
            return StatementPlan(table, bound, sealed)
        }

        private fun splitTopLevel(text: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            for ((i, c) in text.withIndex()) {
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 0) { parts += text.substring(start, i); start = i + 1 }
                }
            }
            parts += text.substring(start)
            return parts
        }

        private fun refuse(sql: String): Nothing =
            throw IllegalStateException("cannot tell which parameter is a sealed column in: ${sql.take(160)}")
    }
}
