package com.fileapex.ui.notes

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.data.bulletin.BulletinRemoteFilePurgeHandler
import com.fileapex.data.bulletin.bulletinDeleteContentKind
import com.fileapex.data.bulletin.hasBulletinBinaryAttachment
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BriefToast
import com.fileapex.ui.dialogs.GoogleDrivePermissionDialog

@Composable
internal fun NotesScope.NotesAttachDialogs() {
    with(frame) { with(live) {
        if (showNotesPermissionPrompt) {
            AlertDialog(
                onDismissRequest = {
                    FileApexServices.settings.setNotesNotificationsEnabled(false)
                    FileApexServices.settings.setNotesNotificationPromptShown(true)
                    val text = pendingNoteToSend
                    pendingNoteToSend = null
                    showNotesPermissionPrompt = false
                    flushPendingSend(text)
                },
                title = { Text(stringRes("enable_note_notifications")) },
                text = { Text(stringRes("note_notifications_prompt")) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            FileApexServices.settings.setNotesNotificationsEnabled(true)
                            FileApexServices.settings.setNotesNotificationPromptShown(true)
                            val text = pendingNoteToSend
                            pendingNoteToSend = null
                            showNotesPermissionPrompt = false
                            flushPendingSend(text)
                        }
                    ) {
                        Text(stringRes("enable_notifications"))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            FileApexServices.settings.setNotesNotificationsEnabled(false)
                            FileApexServices.settings.setNotesNotificationPromptShown(true)
                            val text = pendingNoteToSend
                            pendingNoteToSend = null
                            showNotesPermissionPrompt = false
                            flushPendingSend(text)
                        }
                    ) {
                        Text(stringRes("not_now"))
                    }
                }
            )
        }

        attachError?.let { message ->
            AlertDialog(
                onDismissRequest = { attachError = null },
                title = { Text(stringRes("attachment")) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { attachError = null }) { Text(stringRes("ok")) }
                }
            )
        }

        relayOptIn?.let { offer ->
            val picked = pendingRelayPick
            AlertDialog(
                onDismissRequest = {
                    FileApexServices.settings.setDriveRelayOptInPromptShown(true)
                    relayOptIn = null
                    pendingRelayPick = null
                },
                title = { Text(stringRes("file_too_large_offline_notes")) },
                text = {
                    Text(
                        stringRes(
                            "file_over_notes_limit",
                            offer.fileLabel,
                            offer.lanLimitLabel,
                            offer.relayLimitLabel
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            relayOptIn = null
                            if (picked != null) beginRelayOptIn(picked)
                        }
                    ) { Text(stringRes("enable_drive_relay")) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            FileApexServices.settings.setDriveRelayOptInPromptShown(true)
                            relayOptIn = null
                            pendingRelayPick = null
                        }
                    ) { Text(stringRes("not_now")) }
                }
            )
        }

        if (showDrivePermission) {
            GoogleDrivePermissionDialog(
                onGrant = {
                    showDrivePermission = false
                    launchDriveAuth()
                },
                onDismiss = {
                    showDrivePermission = false
                    pendingAcceptAfterRelay = null
                }
            )
        }

    } }
}

@Composable
internal fun NotesScope.NotesDeleteDialogs() {
    val notesSnackbar = LocalNotesSnackbar.current
    with(frame) { with(live) {
        val targetToDelete = noteToDelete
        if (targetToDelete != null) {
            val deleteKind = targetToDelete.bulletinDeleteContentKind()
            AlertDialog(
                onDismissRequest = { noteToDelete = null },
                title = { Text(deleteKind.dialogTitle) },
                text = {
                    Text(
                        stringRes("delete_entry_scope", deleteKind.entryLabel)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = targetToDelete
                            noteToDelete = null
                            if (target.attachmentPinned) {
                                notesSnackbar.say(AppI18n.t("note_locked_unlock_first"))
                                return@TextButton
                            }
                            if (target.hasBulletinBinaryAttachment()) {
                                pendingRemotePurgeDelete = target
                            } else {
                                viewModel.deleteNoteFromAllDevices(target.noteId)
                            }
                        }
                    ) {
                        Text(stringRes("all_devices_title"), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                val target = targetToDelete
                                noteToDelete = null
                                if (target.attachmentPinned) {
                                    notesSnackbar.say(AppI18n.t("note_locked_unlock_first"))
                                    return@TextButton
                                }
                                viewModel.deleteNoteLocally(target.noteId)
                            }
                        ) {
                            Text(stringRes("this_device_only"))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = { noteToDelete = null }) {
                            Text(stringRes("cancel"))
                        }
                    }
                }
            )
        }

        val remotePurgeDeleteTarget = pendingRemotePurgeDelete
        if (remotePurgeDeleteTarget != null) {
            AlertDialog(
                onDismissRequest = { pendingRemotePurgeDelete = null },
                title = { Text(stringRes("remove_from_remote")) },
                text = {
                    Text(stringRes("also_remove_remote"))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = remotePurgeDeleteTarget
                            pendingRemotePurgeDelete = null
                            viewModel.deleteNoteFromAllDevices(
                                noteId = target.noteId,
                                remotePurge = true
                            )
                        }
                    ) {
                        Text(stringRes("remove_file"), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                val target = remotePurgeDeleteTarget
                                pendingRemotePurgeDelete = null
                                viewModel.deleteNoteFromAllDevices(
                                    noteId = target.noteId,
                                    remotePurge = false
                                )
                            }
                        ) {
                            Text(stringRes("keep_file"))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = { pendingRemotePurgeDelete = null }) {
                            Text(stringRes("cancel"))
                        }
                    }
                }
            )
        }

        val incomingRemotePurgePrompt = remotePurgePrompt
        if (incomingRemotePurgePrompt != null) {
            AlertDialog(
                onDismissRequest = {
                    BulletinRemoteFilePurgeHandler.resolveFirstTimePrompt(
                        deleteFiles = false,
                        localPath = incomingRemotePurgePrompt.localPath
                    )
                    remotePurgePrompt = null
                },
                title = { Text(stringRes("remote_delete_request")) },
                text = {
                    Text(
                        stringRes("remote_delete_prompt_body", incomingRemotePurgePrompt.fileName)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            BulletinRemoteFilePurgeHandler.resolveFirstTimePrompt(
                                deleteFiles = true,
                                localPath = incomingRemotePurgePrompt.localPath
                            )
                            remotePurgePrompt = null
                        }
                    ) {
                        Text(stringRes("delete_files"), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            BulletinRemoteFilePurgeHandler.resolveFirstTimePrompt(
                                deleteFiles = false,
                                localPath = incomingRemotePurgePrompt.localPath
                            )
                            remotePurgePrompt = null
                        }
                    ) {
                        Text(stringRes("keep_files"))
                    }
                }
            )
        }
    } }
}
