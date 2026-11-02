package com.fileapex.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.bulletin.MessageEntity
import com.fileapex.friends.data.FriendLinkEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClusterDataPurgeTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var database: FileApexDatabase

    @Before fun setUp() {
        database = Room.databaseBuilder<FileApexDatabase>(temp.root.resolve("purge.db").absolutePath).setDriver(BundledSQLiteDriver()).build()
    }

    @After fun tearDown() = database.close()

    @Test fun aRemovedDeviceLosesTheBulletinBoardHistoryButKeepsItsFriends() = runBlocking {
        val friend = "ab".repeat(32)
        database.messageDao().upsert(MessageEntity("m1", "mac", "MacBook Pro", "old note", 0, 1_000L, updatedAt = 1_000L))
        database.messageDao().upsert(MessageEntity("m2", "fold", "Fold8", "old link", 0, 2_000L, updatedAt = 2_000L))
        database.friendsDao().putLink(FriendLinkEntity(friend, "Alex", "Friends", 1, "Alex"))
        clearClusterData(database)
        assertNull(database.messageDao().getById("m1"))
        assertNull(database.messageDao().getById("m2"))
        assertEquals(0, database.messageDao().getActiveOnce().size)
        assertNotNull(database.friendsDao().link(friend))
    }
}
