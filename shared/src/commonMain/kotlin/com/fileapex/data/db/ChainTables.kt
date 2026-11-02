package com.fileapex.data.db

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import com.fileapex.membership.ChainRecord
import com.fileapex.membership.ChainStore
import com.fileapex.membership.RecordKind

/** One signed membership record, keyed by its hash. Records of a losing fork branch are kept too. */
@Entity(tableName = "membership_chain", indices = [Index("seq")])
data class ChainRecordEntity(
    @PrimaryKey val hash: String,
    val seq: Long,
    val parentHash: String,
    val kind: String,
    val actor: String,
    val actorKey: String,
    val subject: String,
    val subjectKey: String,
    val tlsPin: String,
    val reason: String,
    val signature: String
)

@Dao
interface ChainDao {
    @Query("SELECT * FROM membership_chain ORDER BY seq ASC, hash ASC")
    suspend fun all(): List<ChainRecordEntity>

    /** The row id, or -1 when a record with this hash is already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ChainRecordEntity): Long

    @Query("DELETE FROM membership_chain")
    suspend fun clear()

    @Query("DELETE FROM membership_chain WHERE hash IN (:hashes)")
    suspend fun delete(hashes: List<String>): Int
}

class RoomChainStore(private val dao: ChainDao) : ChainStore {
    override suspend fun all(): List<ChainRecord> = dao.all().map { it.toRecord() }

    override suspend fun put(record: ChainRecord): Boolean = dao.insert(record.toEntity()) != -1L

    override suspend fun clear() = dao.clear()

    override suspend fun remove(hashes: Set<String>): Int = hashes.chunked(500).sumOf { dao.delete(it) }
}

private fun ChainRecord.toEntity() =
    ChainRecordEntity(hash(), seq, parentHash, kind.name, actor, actorKey, subject, subjectKey, tlsPin, reason, signature)

internal fun ChainRecordEntity.toRecord() =
    ChainRecord(seq, parentHash, RecordKind.valueOf(kind), actor, actorKey, subject, subjectKey, tlsPin, reason, signature)
