package com.fileapex.domain.transfer

import com.fileapex.data.db.PendingTransferEntity
import com.fileapex.data.db.PendingTransferStatus
import com.fileapex.data.db.QueuedSourceSnapshot
import com.fileapex.data.db.QueuedTransferSourceKind
import com.fileapex.domain.transfer.QueuedTransferSources.missingSourceName
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A queued file that was deleted is reported by name, so the queue stops instead of retrying it for ever. */
class MissingQueuedSourceTest {
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

    private fun snapshot(path: String, remoteHost: String? = null) = QueuedSourceSnapshot(
        fileName = File(path).name,
        sizeBytes = 3L,
        absolutePath = path,
        relativeDestPath = File(path).name,
        remoteHost = remoteHost,
        remotePort = if (remoteHost == null) null else 8080
    )

    private fun snapshots(vararg items: QueuedSourceSnapshot) =
        row(QueuedTransferSourceKind.Sources, json.encodeToString(ListSerializer(QueuedSourceSnapshot.serializer()), items.toList()))

    private fun roots(vararg paths: String) =
        row(QueuedTransferSourceKind.LocalRoots, json.encodeToString(ListSerializer(String.serializer()), paths.toList()))

    @Test
    fun aDeletedQueuedFileIsNamed() {
        val dir = createTempDir("queued")
        try {
            val gone = File(dir, "gone-4g.bin")
            assertEquals("gone-4g.bin", missingSourceName(snapshots(snapshot(gone.path))))
            assertEquals("gone-4g.bin", missingSourceName(roots(gone.path)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun filesThatStillExistAreNotMissing() {
        val dir = createTempDir("queued")
        try {
            val there = File(dir, "there.txt").apply { writeText("abc") }
            assertNull(missingSourceName(snapshots(snapshot(there.path))))
            assertNull(missingSourceName(roots(there.path, dir.path)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRemoteSourceIsNotCheckedAgainstTheLocalDisk() {
        assertNull(missingSourceName(snapshots(snapshot("/not/on/this/disk.bin", remoteHost = "192.0.2.10"))))
    }

    @Test
    fun theFirstMissingFileOfSeveralIsNamed() {
        val dir = createTempDir("queued")
        try {
            val there = File(dir, "there.txt").apply { writeText("abc") }
            val gone = File(dir, "gone.txt")
            assertEquals("gone.txt", missingSourceName(snapshots(snapshot(there.path), snapshot(gone.path))))
        } finally {
            dir.deleteRecursively()
        }
    }
}
