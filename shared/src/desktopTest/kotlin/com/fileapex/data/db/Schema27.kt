package com.fileapex.data.db

import androidx.sqlite.SQLiteConnection
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The tables and views of the exported schema 27, for tests that need a file as the build before schema 28 left it. */
internal object Schema27 {
    private val database by lazy {
        Json.parseToJsonElement(File("schemas/com.fileapex.data.db.FileApexDatabase/27.json").readText())
            .jsonObject.getValue("database").jsonObject
    }

    fun create(connection: SQLiteConnection, table: String) {
        val entity = database.getValue("entities").jsonArray.map { it.jsonObject }.first { it.getValue("tableName").jsonPrimitive.content == table }
        connection.prepare(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)).use { it.step() }
        for (index in entity["indices"] as? JsonArray ?: JsonArray(emptyList())) {
            connection.prepare(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)).use { it.step() }
        }
    }

    fun createView(connection: SQLiteConnection, view: String) {
        val entity = database.getValue("views").jsonArray.map { it.jsonObject }.first { it.getValue("viewName").jsonPrimitive.content == view }
        connection.prepare(entity.getValue("createSql").jsonPrimitive.content.replace("\${VIEW_NAME}", view)).use { it.step() }
    }

    /** Takes a file made by the current build back to schema 27: the old `members` with its pin columns, the old views, no local tables. */
    fun rewind(connection: SQLiteConnection) {
        listOf(
            "DROP VIEW IF EXISTS paired_devices_view", "DROP VIEW IF EXISTS removed_devices_view", "DROP VIEW IF EXISTS member_effective",
            "DROP TABLE IF EXISTS member_local", "DROP TABLE IF EXISTS legacy_removed", "DROP TABLE IF EXISTS members"
        ).forEach { connection.prepare(it).use { s -> s.step() } }
        create(connection, "members")
        listOf("paired_devices_view", "removed_devices_view").forEach { createView(connection, it) }
        connection.prepare("PRAGMA user_version = 27").use { it.step() }
    }

    /** The tables and views membership uses, plus the triggers and the unique pin index the app adds on every open. */
    fun createMembership(connection: SQLiteConnection) {
        listOf("members", "membership_meta", "membership_chain", "member_profiles").forEach { create(connection, it) }
        listOf("paired_devices_view", "removed_devices_view").forEach { createView(connection, it) }
        listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS members_active_pin ON members(tlsPin) WHERE state = 'ACTIVE'",
            "CREATE TRIGGER IF NOT EXISTS members_check_insert BEFORE INSERT ON members WHEN NEW.state NOT IN ('ACTIVE','REMOVED','LEFT') OR (NEW.state = 'ACTIVE' AND length(NEW.tlsPin) != 64) OR (NEW.state != 'ACTIVE' AND length(NEW.tlsPin) NOT IN (0, 64)) BEGIN SELECT RAISE(ABORT, 'malformed member row'); END",
            "CREATE TRIGGER IF NOT EXISTS members_check_update BEFORE UPDATE ON members WHEN NEW.state NOT IN ('ACTIVE','REMOVED','LEFT') OR (NEW.state = 'ACTIVE' AND length(NEW.tlsPin) != 64) OR (NEW.state != 'ACTIVE' AND length(NEW.tlsPin) NOT IN (0, 64)) BEGIN SELECT RAISE(ABORT, 'malformed member row'); END"
        ).forEach { connection.prepare(it).use { s -> s.step() } }
    }
}
