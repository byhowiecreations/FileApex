package com.fileapex.data.db

import androidx.sqlite.SQLiteConnection
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The tables of the exported schema 26, for tests that need a file as an older build left it. */
internal object Schema26 {
    private val entities by lazy {
        Json.parseToJsonElement(File("schemas/com.fileapex.data.db.FileApexDatabase/26.json").readText())
            .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray.map { it.jsonObject }
    }

    /** Creates [table] and its indexes exactly as schema 26 had them. */
    fun create(connection: SQLiteConnection, table: String) {
        val entity = entities.first { it.getValue("tableName").jsonPrimitive.content == table }
        connection.prepare(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)).use { it.step() }
        for (index in entity["indices"] as? JsonArray ?: JsonArray(emptyList())) {
            connection.prepare(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)).use { it.step() }
        }
    }

    /** Takes a file made by the current build back to the shape of schema 26: no profile table, no views, no chain, the old device and removed-device tables (the caller recreates `members` as schema 26 had it). */
    fun rewind(connection: SQLiteConnection) {
        listOf(
            "DROP VIEW IF EXISTS paired_devices_view", "DROP VIEW IF EXISTS removed_devices_view", "DROP VIEW IF EXISTS member_effective",
            "DROP TABLE IF EXISTS member_local", "DROP TABLE IF EXISTS legacy_removed",
            "DROP TABLE IF EXISTS member_profiles", "DROP TABLE IF EXISTS membership_chain"
        )
            .forEach { connection.prepare(it).use { s -> s.step() } }
        create(connection, "paired_devices")
        create(connection, "removed_devices")
    }

    /** One `paired_devices` row with every column, as schema 26 stored it. */
    fun insertDevice(
        connection: SQLiteConnection, id: String, name: String = id, removed: Boolean = false, version: Long = 100L,
        pin: String = "", alt: String = "", tlsPort: Int = 0
    ) {
        val removedAt = if (removed) version.toString() else "NULL"
        connection.prepare(
            "INSERT INTO paired_devices VALUES ('$id', '$name', '192.168.1.9', 8080, 'h-$id', '', 0, '/', '', 0, '', '', '', '', '[]', 0, NULL, NULL, 0, '', NULL, NULL, 0, '', " +
                "$version, ${if (removed) 1 else 0}, $removedAt, '', '', '$pin', '$alt', $tlsPort, '')"
        ).use { it.step() }
    }
}
