package com.fileapex.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.DatabaseView
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.fileapex.membership.ChainView
import com.fileapex.membership.InMemoryChainStore
import com.fileapex.membership.LegacyRemoval
import com.fileapex.membership.MemberFacts
import com.fileapex.membership.MemberProjection
import com.fileapex.membership.MemberState
import com.fileapex.membership.MembershipChainLog
import com.fileapex.membership.PairingMarks
import com.fileapex.util.TimeUtils

/**
 * Who is a member of this device's cluster, derived from the signed chain by [MemberProjection] and written only by
 * [MemberStore.refreshMembers]: the state, the identity key, and who removed the device and why. How this device reaches
 * and trusts a member (pin, port, key hash) is in [MemberLocalEntity].
 */
@Entity(tableName = "members", indices = [Index("state")])
data class MemberEntity(
    @PrimaryKey val deviceId: String,
    val state: String,
    /** The member's identity key (SPKI, base64); empty for a member that never ran the chain. */
    val publicKey: String,
    /** Who last removed this device; empty when it is active or the removal did not say. */
    val eventActor: String,
    /** `user_removed` or `left_cluster`; empty when active or the removal did not say. */
    val eventReason: String
)

/** What this device knows about a member that the chain cannot carry. Written by the pin store and the pairing code only. */
@Entity(tableName = "member_local")
data class MemberLocalEntity(
    @PrimaryKey val deviceId: String,
    /** The TLS pin this device trusts for the member; a removed member keeps its last one so it can still read its removal. */
    @ColumnInfo(defaultValue = "''") val tlsPin: String = "",
    /** The pin the member may also present during a key change. */
    @ColumnInfo(defaultValue = "''") val tlsPinAlt: String = "",
    /** The port the member's TLS front listens on, 0 when not known yet. */
    @ColumnInfo(defaultValue = "0") val tlsPort: Int = 0,
    /** The public key hash a removal named, so a removed key cannot come back under a new id. */
    @ColumnInfo(defaultValue = "''") val keyHash: String = "",
    /** When a key-less peer was paired here and its legacy ADMIT was not on the chain yet; 0 when none. */
    @ColumnInfo(defaultValue = "0") val pendingAdmitAt: Long = 0L,
    /** When this device was paired with the member in person and the chain's seq then; they hold back an older removal for a while. */
    @ColumnInfo(defaultValue = "0") val pairedAtEpochMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val pairedChainSeq: Long = 0L
)

/** Ids removed before the chain existed: no record names them, so they cannot be derived. */
@Entity(tableName = "legacy_removed")
data class LegacyRemovedEntity(
    @PrimaryKey val deviceId: String,
    @ColumnInfo(defaultValue = "''") val keyHash: String = "",
    /** The reason the removal was made with; empty for a removal that did not say. */
    @ColumnInfo(defaultValue = "''") val reason: String = "",
    @ColumnInfo(defaultValue = "''") val removedBy: String = ""
)

/** A member with its local facts: the only thing readers of a pin, a port or a key hash look at. */
@DatabaseView(viewName = MemberEffectiveView.NAME, value = MemberEffectiveView.SQL)
data class MemberEffectiveEntity(
    val deviceId: String,
    val state: String,
    val publicKey: String,
    val eventActor: String,
    val eventReason: String,
    val tlsPin: String,
    val tlsPinAlt: String,
    val tlsPort: Int,
    val keyHash: String
)

object MemberEffectiveView {
    const val NAME = "member_effective"

    const val SQL = "SELECT m.deviceId AS deviceId, m.state AS state, m.publicKey AS publicKey, m.eventActor AS eventActor, " +
        "m.eventReason AS eventReason, COALESCE(l.tlsPin, '') AS tlsPin, COALESCE(l.tlsPinAlt, '') AS tlsPinAlt, " +
        "COALESCE(l.tlsPort, 0) AS tlsPort, COALESCE(l.keyHash, '') AS keyHash " +
        "FROM members AS m LEFT JOIN member_local AS l ON l.deviceId = m.deviceId"
}

@Entity(tableName = "membership_meta")
data class MembershipMetaEntity(@PrimaryKey val key: String, val value: String)

/**
 * The one place `members` is written: the table is rebuilt from the chain, the local facts and the pre-chain removals
 * inside the transaction of whatever local write changed one of them. The DAOs that write those inputs extend this, so
 * a write and its projection are one transaction.
 */
interface MemberStore {
    @Query("SELECT * FROM membership_chain ORDER BY seq ASC, hash ASC")
    suspend fun storedChain(): List<ChainRecordEntity>

    @Query("SELECT * FROM members ORDER BY deviceId ASC")
    suspend fun storedMembers(): List<MemberEntity>

    @Query("SELECT * FROM member_local ORDER BY deviceId ASC")
    suspend fun storedLocal(): List<MemberLocalEntity>

    @Query("SELECT * FROM legacy_removed ORDER BY deviceId ASC")
    suspend fun storedLegacyRemoved(): List<LegacyRemovedEntity>

    @Query("SELECT value FROM membership_meta WHERE `key` = :key LIMIT 1")
    suspend fun meta(key: String): String?

    @Query(
        "INSERT INTO members (deviceId, state, publicKey, eventActor, eventReason) VALUES (:deviceId, :state, :publicKey, :eventActor, :eventReason) " +
            "ON CONFLICT(deviceId) DO UPDATE SET state = excluded.state, publicKey = excluded.publicKey, eventActor = excluded.eventActor, eventReason = excluded.eventReason"
    )
    suspend fun writeMember(deviceId: String, state: String, publicKey: String, eventActor: String, eventReason: String)

    @Query("DELETE FROM members WHERE deviceId = :deviceId")
    suspend fun deleteMember(deviceId: String)

    @Query("UPDATE member_local SET pendingAdmitAt = 0 WHERE deviceId IN (:deviceIds)")
    suspend fun clearPendingAdmits(deviceIds: List<String>)

    /** The chain this device follows, walked from the stored records. */
    suspend fun chainView(): ChainView {
        val store = InMemoryChainStore()
        storedChain().forEach { store.put(it.toRecord()) }
        val selfId = meta(SELF_ID_KEY).orEmpty()
        return MembershipChainLog(store) { selfId }.view()
    }

    /** Rebuilds `members` from its inputs and writes only the rows that differ. A member whose pin another active member already holds is not made active. */
    @Transaction
    suspend fun refreshMembers() {
        val view = chainView()
        val local = storedLocal()
        val facts = local.associate { it.deviceId to MemberFacts(it.pendingAdmitAt, it.pairedAtEpochMs, it.pairedChainSeq) }
        val legacy = storedLegacyRemoved().associate { it.deviceId to LegacyRemoval(it.removedBy, it.reason) }
        val wanted = MemberProjection.project(view, facts, legacy, TimeUtils.now())
        val current = storedMembers().associateBy { it.deviceId }
        val pins = local.associate { it.deviceId to it.tlsPin }
        val taken = HashSet<String>()
        val refused = HashSet<String>()
        // A member that is already active keeps its pin; a later claim on the same pin waits.
        val activeFirst = wanted.filter { it.state == MemberState.ACTIVE }.sortedBy { if (current[it.deviceId]?.state == MemberState.ACTIVE.name) 0 else 1 }
        for (row in activeFirst) {
            val pin = pins[row.deviceId].orEmpty()
            if (pin.isNotEmpty() && !taken.add(pin)) refused += row.deviceId
        }
        val keep = wanted.map { it.deviceId }.toSet() + refused
        current.keys.filter { it !in keep }.forEach { deleteMember(it) }
        // Rows that stop being active are written before rows that become active, so a pin changes hands without two holders.
        val changed = wanted.filter { it.deviceId !in refused }
            .map { MemberEntity(it.deviceId, it.state.name, it.publicKey, it.eventActor, it.eventReason) }
            .filter { current[it.deviceId] != it }
            .sortedBy { it.state == MemberState.ACTIVE.name }
        for (row in changed) writeMember(row.deviceId, row.state, row.publicKey, row.eventActor, row.eventReason)
        // An admit the chain has taken is no longer owed.
        local.filter { it.pendingAdmitAt != 0L && view.state.member(it.deviceId) != null }.map { it.deviceId }.chunked(500).forEach { clearPendingAdmits(it) }
    }

    companion object {
        /** The meta key holding this device's id, so the chain's own root is the one followed. */
        const val SELF_ID_KEY = "self_id"
    }
}

@Dao
interface MembershipDao : MemberStore {
    @Query("SELECT * FROM members WHERE deviceId = :deviceId LIMIT 1")
    suspend fun get(deviceId: String): MemberEntity?

    @Query("SELECT * FROM member_effective ORDER BY deviceId ASC")
    suspend fun allEffective(): List<MemberEffectiveEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putProfileIfAbsent(profile: MemberProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: MembershipMetaEntity)

    @Query("SELECT * FROM member_local WHERE deviceId = :deviceId LIMIT 1")
    suspend fun localRow(deviceId: String): MemberLocalEntity?

    suspend fun localFacts(deviceId: String): MemberFacts? =
        localRow(deviceId)?.let { MemberFacts(it.pendingAdmitAt, it.pairedAtEpochMs, it.pairedChainSeq) }

    @Query(
        "INSERT INTO member_local (deviceId, pairedAtEpochMs, pairedChainSeq) VALUES (:deviceId, :now, :seq) " +
            "ON CONFLICT(deviceId) DO UPDATE SET pairedAtEpochMs = excluded.pairedAtEpochMs, pairedChainSeq = excluded.pairedChainSeq"
    )
    suspend fun putHold(deviceId: String, now: Long, seq: Long)

    @Transaction
    suspend fun holdRemoval(deviceId: String, now: Long, seq: Long) {
        putHold(deviceId, now, seq)
        refreshMembers()
    }

    @Query(
        "INSERT INTO member_local (deviceId, tlsPin) VALUES (:deviceId, :pin) " +
            "ON CONFLICT(deviceId) DO UPDATE SET tlsPin = CASE WHEN length(member_local.tlsPin) = 64 THEN member_local.tlsPin ELSE excluded.tlsPin END"
    )
    suspend fun putRevokedPin(deviceId: String, pin: String)

    /** The chain removed [deviceId]: the roster follows, and the pin the record names stays so the device can still connect and read its removal. */
    @Transaction
    suspend fun retainRevokedPin(deviceId: String, pin: String) {
        if (pin.length == 64) putRevokedPin(deviceId, pin)
        refreshMembers()
    }
}

/** The in-person pairing mark of this device in `membership_meta`, so it survives a restart. */
class RoomPairingMarks(private val dao: MembershipDao) : PairingMarks {
    override suspend fun pairedAt(): Long = dao.meta(KEY)?.toLongOrNull() ?: 0L
    override suspend fun markPaired(at: Long) = dao.putMeta(MembershipMetaEntity(KEY, at.toString()))
    override suspend fun clear() = dao.putMeta(MembershipMetaEntity(KEY, "0"))

    private companion object {
        const val KEY = "paired_in_person_at"
    }
}

/**
 * Database rules Room cannot declare: a row's state and a pin are well formed, and two active members never hold one pin.
 * Idempotent, so it runs after the migration that creates the tables and every time the database opens.
 */
object MembershipSchema {
    private const val SAME_PIN_ACTIVE =
        "EXISTS (SELECT 1 FROM member_local a JOIN members am ON am.deviceId = a.deviceId AND am.state = 'ACTIVE' " +
            "JOIN member_local b ON b.tlsPin = a.tlsPin AND b.deviceId != a.deviceId JOIN members bm ON bm.deviceId = b.deviceId AND bm.state = 'ACTIVE' " +
            "WHERE length(a.tlsPin) = 64 AND a.deviceId = %s)"

    val STATEMENTS = listOf(
        "DROP INDEX IF EXISTS members_active_pin",
        """
        CREATE TRIGGER IF NOT EXISTS members_check_insert BEFORE INSERT ON members
        WHEN NEW.state NOT IN ('ACTIVE','REMOVED','LEFT')
        BEGIN SELECT RAISE(ABORT, 'malformed member row'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS members_check_update BEFORE UPDATE ON members
        WHEN NEW.state NOT IN ('ACTIVE','REMOVED','LEFT')
        BEGIN SELECT RAISE(ABORT, 'malformed member row'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS member_local_check_insert BEFORE INSERT ON member_local
        WHEN length(NEW.tlsPin) NOT IN (0, 64) OR length(NEW.tlsPinAlt) NOT IN (0, 64)
        BEGIN SELECT RAISE(ABORT, 'malformed member pin'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS member_local_check_update BEFORE UPDATE ON member_local
        WHEN length(NEW.tlsPin) NOT IN (0, 64) OR length(NEW.tlsPinAlt) NOT IN (0, 64)
        BEGIN SELECT RAISE(ABORT, 'malformed member pin'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS members_one_active_pin_insert AFTER INSERT ON members
        WHEN NEW.state = 'ACTIVE' AND ${SAME_PIN_ACTIVE.format("NEW.deviceId")}
        BEGIN SELECT RAISE(ABORT, 'a pin is held by another active member'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS members_one_active_pin_update AFTER UPDATE ON members
        WHEN NEW.state = 'ACTIVE' AND ${SAME_PIN_ACTIVE.format("NEW.deviceId")}
        BEGIN SELECT RAISE(ABORT, 'a pin is held by another active member'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS member_local_one_active_pin_insert AFTER INSERT ON member_local
        WHEN ${SAME_PIN_ACTIVE.format("NEW.deviceId")}
        BEGIN SELECT RAISE(ABORT, 'a pin is held by another active member'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS member_local_one_active_pin_update AFTER UPDATE ON member_local
        WHEN ${SAME_PIN_ACTIVE.format("NEW.deviceId")}
        BEGIN SELECT RAISE(ABORT, 'a pin is held by another active member'); END
        """.trimIndent()
    )

    fun ensure(connection: SQLiteConnection) = STATEMENTS.forEach { connection.execSQL(it) }
}

class MembershipSchemaCallback : RoomDatabase.Callback() {
    override suspend fun onOpen(connection: SQLiteConnection) = MembershipSchema.ensure(connection)
}
