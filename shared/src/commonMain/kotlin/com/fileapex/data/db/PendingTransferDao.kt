package com.fileapex.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingTransferDao {
    @Query(
        "SELECT * FROM pending_transfers WHERE status = :status " +
            "ORDER BY createdAtEpochMs ASC"
    )
    suspend fun listByStatus(status: String): List<PendingTransferEntity>

    @Query("SELECT * FROM pending_transfers ORDER BY createdAtEpochMs ASC")
    fun observeAll(): Flow<List<PendingTransferEntity>>

    @Query("SELECT COUNT(*) FROM pending_transfers WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    @Query("SELECT * FROM pending_transfers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PendingTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingTransferEntity)

    @Query("DELETE FROM pending_transfers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_transfers")
    suspend fun deleteAll()
}
