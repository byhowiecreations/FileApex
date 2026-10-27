package com.fileapex.domain.transfer

import com.fileapex.data.db.PendingTransferEntity
import com.fileapex.data.db.PendingTransferStatus
import com.fileapex.data.db.QueuedSourceSnapshot
import com.fileapex.data.db.QueuedTransferSourceKind
import com.fileapex.domain.transfer.QueuedTransferSources.absolutePathsFromEntity
import com.fileapex.domain.transfer.QueuedTransferSources.buildDisplayLabel
import com.fileapex.domain.transfer.QueuedTransferSources.changedSourceName
import com.fileapex.domain.transfer.QueuedTransferSources.decodeDeviceIds
import com.fileapex.domain.transfer.QueuedTransferSources.decodeSources
import com.fileapex.domain.transfer.QueuedTransferSources.fileSummary
import com.fileapex.domain.transfer.QueuedTransferSources.pathSummary
import com.fileapex.domain.transfer.QueuedTransferSources.sourceSummaryFromEntity
import com.fileapex.domain.transfer.QueuedTransferSources.toSnapshot
import com.fileapex.domain.transfer.QueuedTransferSources.toSource
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedTransferSourcesTest {
    private val json = Json

    private fun row(kind: QueuedTransferSourceKind, sourceJson: String) = PendingTransferEntity(
        id = "t1",
        createdAtEpochMs = 1L,
        status = PendingTransferStatus.Queued.name,
        sourceKind = kind.name,
        sourceJson = sourceJson,
        pendingDeviceIdsJson = "[]",
        displayLabel = "x → y"
    )

    private fun snapshot(path: String, size: Long = 3L, modified: Long = 0L) = QueuedSourceSnapshot(
        fileName = File(path).name,
        sizeBytes = size,
        absolutePath = path,
        relativeDestPath = File(path).name,
        modifiedEpochMs = modified
    )

    @Test
    fun summariesAndLabelsCountItemsAndDevices() {
        assertEquals("Files", pathSummary(emptyList()))
        assertEquals("a.txt", pathSummary(listOf("/x/y/a.txt")))
        assertEquals("a.txt", pathSummary(listOf("C:\\x\\a.txt")))
        assertEquals("3 items", pathSummary(listOf("/a", "/b", "/c")))
        assertEquals("Files", fileSummary(emptyList()))
        assertEquals("2 files", fileSummary(listOf(snapshot("/a").toSource(), snapshot("/b").toSource())))
        assertEquals("a → devices", buildDisplayLabel("a", emptyList()))
        assertEquals("a → Pad", buildDisplayLabel("a", listOf("Pad")))
        assertEquals("a → 2 devices", buildDisplayLabel("a", listOf("Pad", "Mac")))
    }

    @Test
    fun brokenOrMissingJsonDecodesToEmptyNotAnError() {
        assertEquals(emptyList<String>(), decodeDeviceIds("not json"))
        assertEquals(listOf("d1"), decodeDeviceIds("[\"d1\"]"))
        assertEquals("Files", sourceSummaryFromEntity(row(QueuedTransferSourceKind.LocalRoots, "oops")))
        assertEquals(
            emptyList<String>(),
            absolutePathsFromEntity(row(QueuedTransferSourceKind.Sources, "oops"))
        )
        assertEquals("x → y", sourceSummaryFromEntity(row(QueuedTransferSourceKind.LocalRoots, "[]").copy(sourceKind = "Gone")))
        assertNull(decodeSources(row(QueuedTransferSourceKind.Sources, "oops")) { })
    }

    @Test
    fun absolutePathsComeFromEitherSourceKind() {
        val roots = json.encodeToString(ListSerializer(String.serializer()), listOf("/a", "/b"))
        assertEquals(listOf("/a", "/b"), absolutePathsFromEntity(row(QueuedTransferSourceKind.LocalRoots, roots)))
        val snaps = json.encodeToString(ListSerializer(QueuedSourceSnapshot.serializer()), listOf(snapshot("/c/d.txt")))
        assertEquals(listOf("/c/d.txt"), absolutePathsFromEntity(row(QueuedTransferSourceKind.Sources, snaps)))
    }

    @Test
    fun aLocalFileChangedSinceQueuingIsNamed() {
        val file = File.createTempFile("queued", ".txt").apply { writeText("abc"); deleteOnExit() }
        val unchanged = snapshot(file.absolutePath, size = 3L, modified = file.lastModified())
        val stale = unchanged.copy(sizeBytes = 99L)
        fun rowOf(s: QueuedSourceSnapshot) = row(
            QueuedTransferSourceKind.Sources,
            json.encodeToString(ListSerializer(QueuedSourceSnapshot.serializer()), listOf(s))
        )
        assertNull(changedSourceName(rowOf(unchanged)))
        assertEquals(file.name, changedSourceName(rowOf(stale)))
        assertNull(changedSourceName(rowOf(stale.copy(modifiedEpochMs = 0L))))
        assertNull(changedSourceName(rowOf(stale.copy(remoteHost = "10.0.0.2", remotePort = 1))))
    }

    @Test
    fun snapshotsRoundTripLocalAndRemoteSources() {
        val file = File.createTempFile("queued", ".txt").apply { writeText("abc"); deleteOnExit() }
        val local = MultiCopySource.Local(file.name, 3L, file.absolutePath)
        val back = local.toSnapshot().toSource()
        assertTrue(back is MultiCopySource.Local)
        assertEquals(file.absolutePath, back.absolutePath)
        val remote = MultiCopySource.Remote("r.bin", 9L, "/r.bin", "10.0.0.2", 8443)
        val remoteBack = remote.toSnapshot().toSource()
        assertTrue(remoteBack is MultiCopySource.Remote)
        assertEquals(8443, (remoteBack as MultiCopySource.Remote).port)
    }

    @Test
    fun aMissingQueuedFileKeepsItsPathAndIsNotRewritten() {
        val gone = File(System.getProperty("java.io.tmpdir"), "no-such-queued-file-xyz.bin").absolutePath
        val snaps = json.encodeToString(ListSerializer(QueuedSourceSnapshot.serializer()), listOf(snapshot(gone)))
        var persisted = 0
        val sources = decodeSources(row(QueuedTransferSourceKind.Sources, snaps)) { persisted++ }
        assertEquals(gone, sources!!.single().absolutePath)
        assertEquals(0, persisted)
    }
}
