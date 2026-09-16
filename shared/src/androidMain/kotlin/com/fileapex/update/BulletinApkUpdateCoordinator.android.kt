package com.fileapex.update

import android.content.Context
import android.graphics.BitmapFactory
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.data.note.NoteRecord
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.network.TransferTransactionJournal
import com.fileapex.platform.AndroidNotificationChannels
import com.fileapex.platform.notifyAppUpdateAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

actual object BulletinApkUpdateCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightNoteIds = ConcurrentHashMap.newKeySet<String>()
    private const val NOTIFICATION_ID_PROGRESS = 9182

    actual fun isUpdateInFlight(noteId: String): Boolean =
        noteId.isNotBlank() && inFlightNoteIds.contains(noteId)

    actual fun handleIncomingApkUpdate(note: NoteRecord) {
        val fileName = note.attachmentFileName.orEmpty().trim()
        val version = BulletinApkUpdatePolicy.extractVersionFromApkName(fileName) ?: return
        if (!BulletinApkUpdatePolicy.shouldAutoUpdateNote(fileName, note.noteId, note.epochMs, note.attachmentSizeBytes)) return

        scope.launch {
            val shouldProceed = inFlightNoteIds.add(note.noteId)
            if (!shouldProceed) return@launch
            val sig = BulletinApkUpdatePolicy.buildFileSignature(fileName, note.attachmentSizeBytes, note.epochMs)
            PendingUpdateStore.markProcessedNote(
                noteId = note.noteId,
                timestampEpochMs = note.epochMs,
                signature = sig
            )

            val context = androidAppContextOrNull()
            var wakeLock: PowerManager.WakeLock? = null
            var notificationManager: NotificationManagerCompat? = null

            try {
                if (context != null) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    wakeLock = powerManager?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "fileapex:bulletin_apk_update"
                    )?.apply {
                        setReferenceCounted(false)
                        acquire(10 * 60 * 1000L) // 10-minute safety timeout
                    }

                    AndroidNotificationChannels.ensureAppUpdatesChannel(context)
                    notificationManager = NotificationManagerCompat.from(context)
                    if (notificationManager.areNotificationsEnabled()) {
                        val progressNotification = NotificationCompat.Builder(
                            context,
                            AndroidNotificationChannels.APP_UPDATES
                        )
                            .setSmallIcon(AndroidNotificationChannels.smallIcon)
                            .setLargeIcon(
                                BitmapFactory.decodeResource(
                                    context.resources,
                                    AndroidNotificationChannels.largeIcon
                                )
                            )
                            .setContentTitle(AppI18n.t("channel_app_updates"))
                            .setContentText(AppI18n.t("apk_auto_update_downloading", version))
                            .setProgress(0, 0, true)
                            .setOngoing(true)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .build()

                        runCatching {
                            notificationManager.notify(NOTIFICATION_ID_PROGRESS, progressNotification)
                        }
                    }
                }

                // Download / fetch attachment payload
                val localPath = FileApexServices.noteRepository.fetchAttachmentIfNeeded(note.noteId)
                if (localPath.isNullOrBlank() || !File(localPath).isFile) {
                    println("BulletinApkUpdateCoordinator: fetch failed for $fileName")
                    return@launch
                }

                val apkFile = File(localPath)
                if (apkFile.length() < 1024L) {
                    println("BulletinApkUpdateCoordinator: downloaded APK too small (${apkFile.length()} bytes)")
                    return@launch
                }

                // Prepare installer offer in PendingUpdateStore
                val txId = "bb_${note.noteId}"
                TransferTransactionJournal.recordCompleted(
                    transactionId = txId,
                    senderDeviceId = note.sourceDeviceId,
                    targetPath = localPath,
                    finalPath = localPath,
                    byteSize = apkFile.length(),
                    timestampEpochMs = note.epochMs
                )
                PendingUpdateStore.setLastAttemptedTransactionId(txId)
                PendingUpdateStore.setTransactionInstallStatus(txId, "INSTALLING")
                val offer = PendingUpdateOffer(
                    remoteVersion = version,
                    releaseTitle = "FileApex $version",
                    releaseNotes = note.content.takeIf { it.isNotBlank() },
                    assetName = fileName,
                    assetDownloadUrl = "",
                    assetSizeBytes = apkFile.length(),
                    localFilePath = localPath,
                    originNoteId = note.noteId,
                    transactionId = txId,
                    transactionTimestampEpochMs = note.epochMs
                )
                AppUpdateCoordinator.setPendingOffer(offer)
                com.fileapex.data.bulletin.BulletinRemoteFilePurgeHandler.pruneStaleAutoUpdateApks()

                // Dismiss progress notification
                notificationManager?.cancel(NOTIFICATION_ID_PROGRESS)

                // Release WakeLock strictly before launching installer intent
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                    wakeLock = null
                }

                // Route into the established GitHub update installation manager
                notifyAppUpdateAvailable(offer)

                PendingUpdateStore.setNoteInstallStatus(note.noteId, "PENDING")
                PendingUpdateStore.setLastAttemptedNoteId(note.noteId)
                PendingUpdateStore.setLastAttemptedInstallTimestamp(0L)

                AppUpdateCoordinator.dismissUpdateSheet()
                if (isAppInForeground()) {
                    runCatching {
                        PlatformUpdateInstaller.installAndRelaunch(
                            localFilePath = localPath,
                            remoteVersion = version
                        )
                    }.onFailure { error ->
                        println("BulletinApkUpdateCoordinator: install launch failed - ${error.message}")
                    }
                }
            } catch (error: Throwable) {
                println("BulletinApkUpdateCoordinator: failed processing $fileName - ${error.message}")
                error.printStackTrace()
            } finally {
                // Ensure WakeLock is released
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
                notificationManager?.cancel(NOTIFICATION_ID_PROGRESS)
                inFlightNoteIds.remove(note.noteId)
            }
        }
    }

    actual fun triggerDirectApkInstall(
        localPath: String,
        version: String,
        fileName: String,
        transactionId: String,
        transactionTimestampEpochMs: Long,
        senderDeviceId: String
    ) {
        if (FileApexServices.isPlayStoreBuild) return
        scope.launch {
            val apkFile = File(localPath)
            if (!apkFile.isFile || apkFile.length() < 1024L) return@launch
            val sig = BulletinApkUpdatePolicy.buildFileSignature(fileName, apkFile.length(), apkFile.lastModified())
            PendingUpdateStore.markProcessedFile(sig)
            val matchingNote = FileApexServices.noteRepository.notes.value.firstOrNull {
                it.attachmentFileName == fileName || it.attachmentFileName == apkFile.name
            }
            val originNoteId = matchingNote?.noteId
            if (!originNoteId.isNullOrBlank()) {
                PendingUpdateStore.setLastAttemptedNoteId(originNoteId)
                PendingUpdateStore.setNoteInstallStatus(originNoteId, "PENDING")
            }
            if (transactionId.isNotBlank()) {
                PendingUpdateStore.setLastAttemptedTransactionId(transactionId)
                PendingUpdateStore.setTransactionInstallStatus(transactionId, "PENDING")
                PendingUpdateStore.setLastAttemptedInstallTimestamp(0L)
            }
            val offer = PendingUpdateOffer(
                remoteVersion = version,
                releaseTitle = "FileApex $version",
                releaseNotes = null,
                assetName = fileName,
                assetDownloadUrl = "",
                assetSizeBytes = apkFile.length(),
                localFilePath = localPath,
                originNoteId = originNoteId,
                transactionId = transactionId.takeIf { it.isNotBlank() },
                transactionTimestampEpochMs = transactionTimestampEpochMs.takeIf { it > 0L }
            )
            AppUpdateCoordinator.setPendingOffer(offer)
            notifyAppUpdateAvailable(offer)
            if (isAppInForeground()) {
                runCatching {
                    PlatformUpdateInstaller.installAndRelaunch(
                        localFilePath = localPath,
                        remoteVersion = version
                    )
                }.onFailure { error ->
                    println("BulletinApkUpdateCoordinator: direct install failed - ${error.message}")
                }
            }
        }
    }

    private fun isAppInForeground(): Boolean {
        val context = androidAppContextOrNull()
        if (context != null) {
            val appProcessInfo = android.app.ActivityManager.RunningAppProcessInfo()
            runCatching {
                android.app.ActivityManager.getMyMemoryState(appProcessInfo)
            }
            if (appProcessInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return true
            }
        }
        return runCatching {
            FileApexServices.isDatabaseReady() && FileApexServices.presenceMonitor.isAppInForeground()
        }.getOrDefault(false)
    }
}
