package com.fileapex.domain.transfer

import com.fileapex.data.db.PendingTransferDao
import com.fileapex.data.db.PendingTransferEntity
import com.fileapex.data.db.PendingTransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferQueuePauseAllTest {

    @Test
    fun togglesQueuedSendingAndPausedRows() = runBlocking {
        val dao = FakePendingTransferDao()
        var drainCalls = 0
        val cancelledIds = mutableListOf<String>()

        assertNull(
            toggleQueuePauseAll(
                dao = dao,
                cancelSending = { id -> cancelledIds += id; true },
                scheduleDrain = { drainCalls++ },
                pauseErrorTag = "user_cancelled"
            )
        )

        dao.upsert(entity(id = "q1", status = PendingTransferStatus.Queued))
        dao.upsert(entity(id = "s1", status = PendingTransferStatus.Sending))

        val pausedResult = toggleQueuePauseAll(
            dao = dao,
            cancelSending = { id -> cancelledIds += id; false },
            scheduleDrain = { drainCalls++ },
            pauseErrorTag = "user_cancelled"
        )
        assertEquals(true, pausedResult)
        assertEquals(PendingTransferStatus.Paused.name, dao.getById("q1")?.status)
        assertEquals(PendingTransferStatus.Paused.name, dao.getById("s1")?.status)
        assertEquals(listOf("s1"), cancelledIds)

        val resumedResult = toggleQueuePauseAll(
            dao = dao,
            cancelSending = { false },
            scheduleDrain = { drainCalls++ },
            pauseErrorTag = "user_cancelled"
        )
        assertEquals(false, resumedResult)
        assertEquals(PendingTransferStatus.Queued.name, dao.getById("q1")?.status)
        assertEquals(PendingTransferStatus.Queued.name, dao.getById("s1")?.status)
        assertEquals(1, drainCalls)
    }

    private fun entity(id: String, status: PendingTransferStatus) = PendingTransferEntity(
        id = id,
        createdAtEpochMs = 1_000L,
        status = status.name,
        sourceKind = "Files",
        sourceJson = "[]",
        pendingDeviceIdsJson = """["peer-1"]""",
        displayLabel = "file -> peer-1",
        lastError = null,
        lastAttemptEpochMs = 500L,
        attemptCount = 1
    )

    private class FakePendingTransferDao : PendingTransferDao {
        private val rows = linkedMapOf<String, PendingTransferEntity>()
        private val flow = MutableStateFlow<List<PendingTransferEntity>>(emptyList())

        override suspend fun listByStatus(status: String): List<PendingTransferEntity> =
            rows.values.filter { it.status == status }

        override fun observeAll(): Flow<List<PendingTransferEntity>> = flow

        override suspend fun listCreatedBefore(cutoffEpochMs: Long): List<PendingTransferEntity> =
            rows.values.filter { it.createdAtEpochMs < cutoffEpochMs }

        override suspend fun getById(id: String): PendingTransferEntity? = rows[id]

        override suspend fun upsert(entity: PendingTransferEntity) {
            rows[entity.id] = entity
            flow.value = rows.values.toList()
        }

        override suspend fun deleteById(id: String) {
            rows.remove(id)
            flow.value = rows.values.toList()
        }

        override suspend fun deleteAll() {
            rows.clear()
            flow.value = emptyList()
        }
    }
}
