package com.fileapex.cloud

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import com.fileapex.platform.FileApexAndroidBootstrap

/**
 * Auto Backup still owns the payload (data_extraction_rules / backup_rules).
 * onBackup/onRestore stay empty because fullBackupOnly uses the XML include list.
 * onRestoreFinished fetches the restore key as soon as Google Backup finishes.
 */
class FileApexBackupAgent : BackupAgent() {
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) = Unit

    override fun onRestoreFinished() {
        FileApexAndroidBootstrap.ensureInitialized(applicationContext)
    }
}
