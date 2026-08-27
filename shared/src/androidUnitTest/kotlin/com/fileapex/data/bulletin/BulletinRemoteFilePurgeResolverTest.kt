package com.fileapex.data.bulletin

import com.fileapex.platform.UniqueFileNames
import com.fileapex.util.sha256HexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BulletinRemoteFilePurgeResolverTest {

    @Test
    fun ignoresOriginPathOutsideDownloads() {
        val downloads = File.createTempFile("fa-dl", "").apply {
            delete()
            mkdirs()
        }
        val origin = File.createTempFile("fa-origin", ".apk").apply {
            writeBytes(ByteArray(64) { 7 })
        }
        val meta = BulletinFileMetadata(
            fileName = origin.name,
            sizeBytes = origin.length(),
            sha256 = sha256HexFile(origin.absolutePath),
            originNode = "mac",
            localPath = origin.absolutePath
        )
        assertNull(BulletinRemoteFilePurgeResolver.resolve(meta, downloads.absolutePath))
        assertTrue(origin.exists())
        origin.delete()
        downloads.deleteRecursively()
    }

    @Test
    fun findsDownloadCopyByNameAndHashWhenOriginPathIsForeign() {
        val downloads = File.createTempFile("fa-dl", "").apply {
            delete()
            mkdirs()
        }
        val copy = File(downloads, "FileApex-v0.9.3a.apk").apply {
            writeBytes(ByteArray(128) { 9 })
        }
        val originPath = "/tmp/origin-device/FileApex-v0.9.3a.apk"
        val meta = BulletinFileMetadata(
            fileName = copy.name,
            sizeBytes = copy.length(),
            sha256 = sha256HexFile(copy.absolutePath),
            originNode = "mac",
            localPath = originPath
        )
        assertEquals(
            copy.absolutePath,
            BulletinRemoteFilePurgeResolver.resolve(meta, downloads.absolutePath)
        )
        copy.delete()
        downloads.deleteRecursively()
    }

    @Test
    fun findsCollisionNameInDownloads() {
        val downloads = File.createTempFile("fa-dl", "").apply {
            delete()
            mkdirs()
        }
        val copy = File(downloads, "notes.pdf").apply { writeBytes(ByteArray(32) { 1 }) }
        val collided = File(downloads, "notes (1).pdf").apply { writeBytes(ByteArray(48) { 2 }) }
        val meta = BulletinFileMetadata(
            fileName = "notes.pdf",
            sizeBytes = collided.length(),
            sha256 = sha256HexFile(collided.absolutePath),
            originNode = "peer"
        )
        assertEquals(
            collided.absolutePath,
            BulletinRemoteFilePurgeResolver.resolve(meta, downloads.absolutePath)
        )
        copy.delete()
        collided.delete()
        downloads.deleteRecursively()
    }

    @Test
    fun skipsHashMismatchInDownloads() {
        val downloads = File.createTempFile("fa-dl", "").apply {
            delete()
            mkdirs()
        }
        val copy = File(downloads, "same-name.bin").apply { writeBytes(ByteArray(16) { 3 }) }
        val meta = BulletinFileMetadata(
            fileName = copy.name,
            sizeBytes = copy.length(),
            sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            originNode = "peer",
            localPath = copy.absolutePath
        )
        assertNull(BulletinRemoteFilePurgeResolver.resolve(meta, downloads.absolutePath))
        copy.delete()
        downloads.deleteRecursively()
    }

    @Test
    fun usesStoredPathWhenInsideDownloadsAndHashMatches() {
        val downloads = File.createTempFile("fa-dl", "").apply {
            delete()
            mkdirs()
        }
        val copy = File(downloads, "local.dat").apply { writeBytes(ByteArray(24) { 4 }) }
        val meta = BulletinFileMetadata(
            fileName = copy.name,
            sizeBytes = copy.length(),
            sha256 = sha256HexFile(copy.absolutePath),
            originNode = "self",
            localPath = copy.absolutePath
        )
        assertEquals(
            copy.absolutePath,
            BulletinRemoteFilePurgeResolver.resolve(meta, downloads.absolutePath)
        )
        copy.delete()
        downloads.deleteRecursively()
    }
}

class BulletinRemoteFilePurgePolicyTest {
    @Test
    fun onlyAndroidReceiversScrub() {
        assertFalse(
            BulletinRemoteFilePurgePolicy.shouldScrubLocalCopy(
                isAndroid = false,
                selfDeviceId = "mac",
                originNode = "mac",
                messageOriginDeviceId = "mac"
            )
        )
        assertFalse(
            BulletinRemoteFilePurgePolicy.shouldScrubLocalCopy(
                isAndroid = false,
                selfDeviceId = "mac",
                originNode = "phone",
                messageOriginDeviceId = "phone"
            )
        )
        assertFalse(
            BulletinRemoteFilePurgePolicy.shouldScrubLocalCopy(
                isAndroid = true,
                selfDeviceId = "fold8",
                originNode = "fold8",
                messageOriginDeviceId = "fold8"
            )
        )
        assertTrue(
            BulletinRemoteFilePurgePolicy.shouldScrubLocalCopy(
                isAndroid = true,
                selfDeviceId = "fold8",
                originNode = "mac",
                messageOriginDeviceId = "mac"
            )
        )
    }
}

class UniqueFileNamesCollisionTest {
    @Test
    fun matchesGeneratedCollisionNames() {
        assertTrue(UniqueFileNames.matchesOriginalOrCollision("photo.jpg", "photo.jpg"))
        assertTrue(UniqueFileNames.matchesOriginalOrCollision("photo.jpg", "photo (1).jpg"))
        assertTrue(UniqueFileNames.matchesOriginalOrCollision("photo.jpg", "photo (12).jpg"))
        assertFalse(UniqueFileNames.matchesOriginalOrCollision("photo.jpg", "photo-copy.jpg"))
        assertFalse(UniqueFileNames.matchesOriginalOrCollision("photo.jpg", "other.jpg"))
    }
}

class BulletinOutboxDrainPolicyTest {
    @Test
    fun skipsOfflineAndLegacyPeers() {
        assertFalse(
            BulletinOutboxDrainPolicy.shouldAttemptPeer(
                supportsBulletinSync = true,
                host = "172.16.16.130",
                port = 8080,
                isOnline = false
            )
        )
        assertFalse(
            BulletinOutboxDrainPolicy.shouldAttemptPeer(
                supportsBulletinSync = false,
                host = "172.16.16.130",
                port = 8080,
                isOnline = true
            )
        )
        assertFalse(
            BulletinOutboxDrainPolicy.shouldAttemptPeer(
                supportsBulletinSync = true,
                host = "",
                port = 8080,
                isOnline = true
            )
        )
        assertTrue(
            BulletinOutboxDrainPolicy.shouldAttemptPeer(
                supportsBulletinSync = true,
                host = "172.16.16.130",
                port = 8080,
                isOnline = true
            )
        )
    }
}
