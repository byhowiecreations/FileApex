package com.fileapex.data.db.seal

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.bulletin.BulletinContentType
import com.fileapex.data.bulletin.ChecklistItemEntity
import com.fileapex.data.bulletin.MessageEntity
import com.fileapex.data.bulletin.ProcessedPacketEntity
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.PeerCandidateEntity
import com.fileapex.data.db.getAllDevicesOnce
import com.fileapex.data.db.PendingTransferEntity
import com.fileapex.data.db.RemovedDeviceEntity
import com.fileapex.friends.data.FriendIncomingEntity
import com.fileapex.friends.data.FriendLinkEntity
import com.fileapex.friends.data.byLabel
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The real database through the sealing driver: callers see plain text, the file holds none of it. */
class SealedDatabaseTest {
    private val dir: File = Files.createTempDirectory("fileapex-sealed").toFile().also { it.deleteOnExit() }
    private val file = File(dir, "fileapex.db")
    private val sealer = FieldSealer(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun open(key: FieldSealer?): FileApexDatabase =
        Room.databaseBuilder<FileApexDatabase>(file.absolutePath)
            .setDriver(SealingSqliteDriver(BundledSQLiteDriver(), { key }))
            .addCallback(SealingPassCallback { key })
            .build()

    /** The database as an older build wrote it: no sealing at all. */
    private fun openAsBefore(): FileApexDatabase =
        Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()

    private fun rawRows(sql: String): List<List<String?>> {
        val c: SQLiteConnection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            return c.prepare(sql).use { s ->
                val out = mutableListOf<List<String?>>()
                while (s.step()) out += (0 until s.getColumnCount()).map { if (s.isNull(it)) null else s.getText(it) }
                out
            }
        } finally { c.close() }
    }

    private fun assertSealed(sql: String, vararg wanted: String) {
        val row = rawRows(sql).single()
        wanted.forEachIndexed { i, plain ->
            assertTrue("column $i is not sealed: ${row[i]}", row[i]!!.startsWith(FieldSealer.PREFIX))
            assertFalse(row[i]!!.contains(plain))
        }
    }

    private suspend fun fill(db: FileApexDatabase) {
        db.messageDao().upsert(MessageEntity("m1", "devA", "Ann Phone", "buy milk and eggs", BulletinContentType.TEXT, 1000L))
        db.transactionDao().applyChecklistItem(ChecklistItemEntity("i1", "m1", "oat milk", false, false, 1, 10, "devA"), 20L)
        db.friendsDao().putLink(FriendLinkEntity("k1", "Grandma", "Family", 5L, "Grandma"))
        db.friendsDao().putIncoming(FriendIncomingEntity("t1", "k1", "taxes.pdf", 10L, "aa", "RECEIVED", 10L, "/inbox/Grandma/taxes.pdf", 6L))
        db.pendingTransferDao().upsert(PendingTransferEntity("p1", 1L, "Queued", "LocalRoots", "[\"/home/me/secret.txt\"]", "[]", "secret.txt to Laptop", lastError = "no route to Laptop"))
        db.peerCandidateDao().put(PeerCandidateEntity("d9", "d1", "MDNS", 1L, 2L, 0, 0L, "{\"name\":\"Kitchen iPad\"}"))
        db.deviceDao().insertRemovedDevice(RemovedDeviceEntity("d8", "hash", "192.168.1.77", 8080, 5L))
    }

    @Test
    fun everythingWrittenThroughTheDaosIsSealedOnDiskAndReadsBackPlain() = runBlocking {
        val db = open(sealer)
        fill(db)
        val message = db.messageDao().getById("m1")!!
        assertEquals("Ann Phone", message.senderName); assertEquals("buy milk and eggs", message.content)
        assertEquals("oat milk", db.checklistItemDao().get("i1")!!.text)
        assertEquals("Grandma", db.friendsDao().link("k1")!!.label)
        assertEquals("taxes.pdf", db.friendsDao().incoming("t1")!!.fileName)
        val transfer = db.pendingTransferDao().getById("p1")!!
        assertEquals("secret.txt to Laptop", transfer.displayLabel); assertEquals("no route to Laptop", transfer.lastError)
        assertEquals("{\"name\":\"Kitchen iPad\"}", db.peerCandidateDao().get("d9")!!.deviceJson)
        assertEquals("192.168.1.77", db.deviceDao().getAllRemovedDevices().single().lastKnownIp)
        db.close()
        assertSealed("SELECT senderName, content FROM messages", "Ann Phone", "milk")
        assertSealed("SELECT text FROM checklist_items", "oat")
        assertSealed("SELECT label, groupName, folderName FROM friend_links", "Grandma", "Family", "Grandma")
        assertSealed("SELECT fileName, quarantinePath FROM friend_incoming", "taxes", "inbox")
        assertSealed("SELECT sourceJson, displayLabel, lastError FROM pending_transfers", "secret", "secret", "route")
        assertSealed("SELECT deviceJson FROM peer_candidates", "Kitchen")
        assertSealed("SELECT lastKnownIp FROM removed_devices", "192.168")
        assertEquals("m1", rawRows("SELECT id FROM messages").single().single())
    }

    @Test
    fun pairedDevicesAreSealedAndListedByTheirReadableName() = runBlocking {
        val db = open(sealer)
        fun dev(id: String, name: String) = PairedDeviceEntity(
            deviceId = id, deviceName = name, lastKnownIp = "10.1.2.$id", port = 8080, publicKeyHash = "h$id", rootPath = "/Users/me/Shared$id",
            deviceMake = "Honor", deviceModel = "Magic8", tailnetHostname = "host$id.ts.net", tailnetIpv4 = "100.64.0.$id"
        )
        db.deviceDao().upsertDevice(dev("3", "zulu")); db.deviceDao().upsertDevice(dev("1", "Alpha")); db.deviceDao().upsertDevice(dev("2", "bravo"))
        db.deviceDao().renameDevice("2", "Bravo Two")
        db.deviceDao().updateEndpoint("1", "10.9.9.9", 9090)
        assertEquals(listOf("Alpha", "Bravo Two", "zulu"), db.deviceDao().getAllDevicesOnce().map { it.deviceName })
        assertEquals("10.9.9.9", db.deviceDao().getDevice("1")!!.lastKnownIp)
        assertEquals("host3.ts.net", db.deviceDao().getDevice("3")!!.tailnetHostname)
        db.close()
        val row = rawRows("SELECT deviceName, lastKnownIp, rootPath, deviceMake, deviceModel, tailnetHostname, tailnetIpv4 FROM paired_devices WHERE deviceId = '1'").single()
        row.forEach { assertTrue("not sealed: $it", it!!.startsWith(FieldSealer.PREFIX)) }
        assertEquals("8080", rawRows("SELECT port FROM paired_devices WHERE deviceId = '3'").single().single())
        assertEquals("h1", rawRows("SELECT publicKeyHash FROM paired_devices WHERE deviceId = '1'").single().single())
    }

    @Test
    fun theSyncBatchTransactionSealsToo() = runBlocking {
        val db = open(sealer)
        db.transactionDao().ingestSyncBatch(
            listOf(MessageEntity("m2", "devB", "Bob Laptop", "synced secret", BulletinContentType.TEXT, 2000L)),
            emptyList(), ProcessedPacketEntity("pk1", 1L),
            checklistItems = listOf(ChecklistItemEntity("i2", "m2", "synced line", false, false, 1, 10, "devB"))
        )
        assertEquals("synced secret", db.messageDao().getById("m2")!!.content)
        db.close()
        assertSealed("SELECT senderName, content FROM messages", "Bob", "synced")
        assertSealed("SELECT text FROM checklist_items", "synced")
    }

    @Test
    fun rowsWrittenBeforeSealingAreSealedWhenTheDatabaseOpensWithAKey() = runBlocking {
        val plain = openAsBefore()
        fill(plain)
        plain.close()
        assertEquals("buy milk and eggs", rawRows("SELECT content FROM messages").single().single())
        val db = open(sealer)
        assertEquals("buy milk and eggs", db.messageDao().getById("m1")!!.content)
        db.close()
        assertSealed("SELECT senderName, content FROM messages", "Ann Phone", "milk")
        assertSealed("SELECT sourceJson, displayLabel, lastError FROM pending_transfers", "secret", "secret", "route")
        val again = open(sealer)
        val before = rawRows("SELECT content FROM messages").single().single()
        again.messageDao().getById("m1")
        again.close()
        assertEquals("a second open must not seal again", before, rawRows("SELECT content FROM messages").single().single())
    }

    @Test
    fun withoutAKeySealedRowsReadEmptyAndNothingIsWrittenInClearText() = runBlocking {
        val db = open(sealer)
        fill(db)
        db.close()
        val locked = open(null)
        assertEquals("", locked.messageDao().getById("m1")!!.content)
        assertThrows(SealedStoreLockedException::class.java) {
            runBlocking { locked.messageDao().upsert(MessageEntity("m3", "devA", "Cy", "must not land", BulletinContentType.TEXT, 3000L)) }
        }
        locked.close()
        assertTrue(rawRows("SELECT content FROM messages WHERE id = 'm3'").isEmpty())
        assertFalse(rawRows("SELECT content FROM messages").joinToString().contains("must not land"))
        val back = open(sealer)
        assertEquals("buy milk and eggs", back.messageDao().getById("m1")!!.content)
        back.close()
    }

    @Test
    fun friendsListIsOrderedByTheReadableLabel() = runBlocking {
        val db = open(sealer)
        db.friendsDao().putLink(FriendLinkEntity("k2", "zed", "Friends", 1L, "zed"))
        db.friendsDao().putLink(FriendLinkEntity("k3", "Amy", "Friends", 2L, "Amy"))
        db.friendsDao().putLink(FriendLinkEntity("k4", "bob", "Friends", 3L, "bob"))
        val labels = db.friendsDao().observeLinks().byLabel().first().map { it.label }
        db.close()
        assertEquals(listOf("Amy", "bob", "zed"), labels)
    }
}
