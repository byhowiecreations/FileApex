package com.fileapex.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinApkUpdatePolicyTest {

    @Test
    fun matchesValidFileApexApkPattern() {
        assertTrue(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v0.9.8a.apk"))
        assertTrue(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v1.0.0.apk"))
        assertTrue(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v12.34.56.apk"))
        assertTrue(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v0.9.7a.apk"))
        assertTrue(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v2.1.0BETA.apk"))
    }

    @Test
    fun rejectsNonMatchingFilesAndApks() {
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk(null))
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk(""))
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk("   "))
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk("app-debug.apk"))
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-0.9.8.apk")) // missing 'v'
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v1.0.apk")) // 2 digits instead of 3
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v1.0.0.zip"))
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v1.0.0.dmg"))
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk(".DS_Store"))
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v1.0.0-release.apk"))
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v1.0.0.apk.part"))
        assertFalse(BulletinApkUpdatePolicy.matchesAutoUpdateApk("FileApex-v12.34.56rc1.apk")) // 'rc1' has digit after 3rd number
    }

    @Test
    fun extractsVersionProperly() {
        assertEquals("v0.9.8a", BulletinApkUpdatePolicy.extractVersionFromApkName("FileApex-v0.9.8a.apk"))
        assertEquals("v1.0.0", BulletinApkUpdatePolicy.extractVersionFromApkName("FileApex-v1.0.0.apk"))
        assertEquals("v2.1.0BETA", BulletinApkUpdatePolicy.extractVersionFromApkName("FileApex-v2.1.0BETA.apk"))
        assertNull(BulletinApkUpdatePolicy.extractVersionFromApkName("app-debug.apk"))
    }

    @Test
    fun shouldAutoUpdateChecksNoteAndSignatureStatus() {
        assertTrue(BulletinApkUpdatePolicy.shouldAutoUpdateNote("FileApex-v0.9.9a.apk", "note-1", 1000L, 5000L))
        
        val sig = BulletinApkUpdatePolicy.buildFileSignature("FileApex-v0.9.9a.apk", 5000L, 1000L)
        PendingUpdateStore.markProcessedNote("note-1", 1000L, sig)
        
        // Same noteId or signature -> already processed
        assertFalse(BulletinApkUpdatePolicy.shouldAutoUpdateNote("FileApex-v0.9.9a.apk", "note-1", 1000L, 5000L))
        
        // New note with different noteId and newer timestamp -> should update even for same version
        assertTrue(BulletinApkUpdatePolicy.shouldAutoUpdateNote("FileApex-v0.9.9a.apk", "note-2", 2000L, 5050L))
        
        // Recalling note-1 cleans up tracker
        PendingUpdateStore.removeProcessedNote("note-1")
        PendingUpdateStore.removeProcessedFile(sig)
        assertTrue(BulletinApkUpdatePolicy.shouldAutoUpdateNote("FileApex-v0.9.9a.apk", "note-1", 3000L, 5000L))
    }

    @Test
    fun saveNullDoesNotClearProcessedTracking() {
        val sig = BulletinApkUpdatePolicy.buildFileSignature("FileApex-v0.9.10a.apk", 6000L, 2000L)
        PendingUpdateStore.markProcessedNote("note-preserved", 2000L, sig)
        
        // Clearing pending offer must NOT wipe processed note tracking
        PendingUpdateStore.save(null)
        
        assertFalse(BulletinApkUpdatePolicy.shouldAutoUpdateNote("FileApex-v0.9.10a.apk", "note-preserved", 2000L, 6000L))
        assertNull(PendingUpdateStore.load())
    }

    @Test
    fun updateInFlightTracksNoteId() {
        assertFalse(BulletinApkUpdateCoordinator.isUpdateInFlight(""))
        assertFalse(BulletinApkUpdateCoordinator.isUpdateInFlight("note-flight-test"))
    }

    @Test
    fun noteInstallStatusTracksAndSuppressesAutoUpdate() {
        val noteId = "note-install-test-123"
        val fileName = "FileApex-v0.9.13a.apk"
        assertNull(PendingUpdateStore.getNoteInstallStatus(noteId))
        assertFalse(PendingUpdateStore.isNoteInstalled(noteId))

        PendingUpdateStore.setNoteInstallStatus(noteId, "NOT_INSTALLED")
        assertEquals("NOT_INSTALLED", PendingUpdateStore.getNoteInstallStatus(noteId))
        assertFalse(PendingUpdateStore.isNoteInstalled(noteId))
        assertFalse(BulletinApkUpdatePolicy.shouldAutoUpdateNote(fileName, noteId, 5000L, 1024L))

        PendingUpdateStore.setNoteInstallStatus(noteId, "INSTALLED")
        assertEquals("INSTALLED", PendingUpdateStore.getNoteInstallStatus(noteId))
        assertTrue(PendingUpdateStore.isNoteInstalled(noteId))
        assertFalse(BulletinApkUpdatePolicy.shouldAutoUpdateNote(fileName, noteId, 5000L, 1024L))

        PendingUpdateStore.setLastAttemptedNoteId(noteId)
        assertEquals(noteId, PendingUpdateStore.getLastAttemptedNoteId())
        PendingUpdateStore.setLastAttemptedNoteId("")
        assertEquals("", PendingUpdateStore.getLastAttemptedNoteId())
    }
}
