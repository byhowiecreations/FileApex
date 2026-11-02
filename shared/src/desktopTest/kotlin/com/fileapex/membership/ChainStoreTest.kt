package com.fileapex.membership

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.RoomChainStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

abstract class ChainStoreTest {
    protected abstract fun create(): ChainStore

    private fun record(seq: Long, parent: String, subject: String) =
        ChainRecord(seq, parent, RecordKind.ADMIT, "mac", "key-mac", subject, "key-$subject", "", "paired", "sig-$subject")

    @Test
    fun aNewRecordIsStoredOnceAndComesBackUnchanged() = runBlocking {
        val store = create()
        val r = record(2, "p", "phone")
        assertTrue(store.put(r))
        assertFalse(store.put(r))
        assertEquals(listOf(r), store.all())
    }

    @Test
    fun twoRecordsWithOneParentAreBothKept() = runBlocking {
        val store = create()
        store.put(record(2, "p", "phone"))
        store.put(record(2, "p", "tablet"))
        assertEquals(setOf("phone", "tablet"), store.all().map { it.subject }.toSet())
    }

    @Test
    fun removingDeletesOnlyTheNamedRecordsAndCountsWhatWasStored() = runBlocking {
        val store = create()
        val phone = record(2, "p", "phone")
        val tablet = record(2, "p", "tablet")
        store.put(phone)
        store.put(tablet)
        assertEquals(1, store.remove(setOf(phone.hash(), "never-stored")))
        assertEquals(listOf(tablet), store.all())
        assertEquals(0, store.remove(emptySet()))
    }

    @Test
    fun clearingEmptiesTheStoreSoANewPairingStartsClean() = runBlocking {
        val store = create()
        store.put(record(2, "p", "phone"))
        store.clear()
        assertTrue(store.all().isEmpty())
        assertTrue(store.put(record(2, "p", "phone")))
    }
}

class InMemoryChainStoreTest : ChainStoreTest() {
    override fun create(): ChainStore = InMemoryChainStore()
}

class RoomChainStoreTest : ChainStoreTest() {
    private val dbs = mutableListOf<FileApexDatabase>()

    override fun create(): ChainStore {
        val file = File.createTempFile("chain", ".db").also { it.deleteOnExit() }
        val db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build().also { dbs += it }
        return RoomChainStore(db.chainDao())
    }

    @After
    fun close() = dbs.forEach { runCatching { it.close() } }
}
