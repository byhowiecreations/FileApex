package com.fileapex.platform

import android.Manifest
import android.os.Build
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStorageAccessPolicyTest {

    @Test
    fun android10DoesNotRequireWriteExternalStorage() {
        val sdkQ = Build.VERSION_CODES.Q
        assertFalse(AndroidStorageAccessPolicy.usesManageAllFiles(sdkQ))
        assertFalse(AndroidStorageAccessPolicy.requiresLegacyWrite(sdkQ))
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            AndroidStorageAccessPolicy.runtimePermissionNames(sdkQ)
        )
    }

    @Test
    fun pieStillRequestsReadAndWrite() {
        val sdkP = Build.VERSION_CODES.P
        assertTrue(AndroidStorageAccessPolicy.requiresLegacyWrite(sdkP))
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            AndroidStorageAccessPolicy.runtimePermissionNames(sdkP)
        )
    }

    @Test
    fun android11PlusUsesAllFilesAccessNotRuntimeStorage() {
        assertTrue(AndroidStorageAccessPolicy.usesManageAllFiles(Build.VERSION_CODES.R))
        assertTrue(AndroidStorageAccessPolicy.usesManageAllFiles(35))
        assertArrayEquals(
            emptyArray<String>(),
            AndroidStorageAccessPolicy.runtimePermissionNames(Build.VERSION_CODES.R)
        )
    }
}
