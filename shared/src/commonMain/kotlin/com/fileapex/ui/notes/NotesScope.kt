package com.fileapex.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.drive.DriveRelayPolicy
import com.fileapex.cloud.drive.GoogleDriveAuth
import com.fileapex.cloud.drive.NotesAttachmentDecision
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.friends.ui.rememberFriendFilePicker
import com.fileapex.platform.PickedLocalFile
import com.fileapex.platform.rememberGoogleDriveAuthLauncher
import com.fileapex.platform.rememberGoogleSignInLauncher
import com.fileapex.presentation.NotesListRow
import com.fileapex.presentation.NotesNewDivider
import com.fileapex.presentation.NotesViewModel
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.KineticPalette
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NotesScope(val frame: NotesFrame, val live: NotesLive)

@Composable
internal fun rememberNotesScope(
    onBack: () -> Unit,
    focusNoteId: String?,
    onFocusNoteConsumed: () -> Unit,
    viewModel: NotesViewModel
): NotesScope {
    val live = remember { NotesLive() }
    val holder = remember { arrayOfNulls<NotesScope>(1) }
    val state by viewModel.uiState.collectAsState()
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme.traits.glassNotesSurfaces
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // What was unread when this screen opened; the divider goes away with the screen, so it never outlives a visit.
    val unreadIds by rememberUnreadOnEntry()
    val visualRows = with(live) {
    val visualRows = remember(state.visualRows, transport?.assemblingNoteId, transport?.settled, unreadIds) {
        val hideId = transport?.takeIf { it.settled != true }?.assemblingNoteId
        val shown = if (hideId.isNullOrBlank()) {
            state.visualRows
        } else {
            state.visualRows.filterNot { row -> row is NotesListRow.Bubble && row.note.noteId == hideId }
        }
        NotesNewDivider.insert(shown, unreadIds)
    }
        visualRows
    }
    val holdingTransportSlot = with(live) {
    pendingTransport != null ||
        (transport != null && transport?.settled != true)
    }
    val (launchDriveAuth, launchSignIn, pickAttachment) = rememberNotesLaunchers(live, holder, scope)
    val listState = rememberLazyListState()
    val backgroundColor = when (currentTheme) {
        AppTheme.FLUX_GLASS -> Color.Transparent
        AppTheme.KINETIC_SPHERE -> KineticPalette.notesBackground
        else -> MaterialTheme.colorScheme.background
    }
    val cardBg = when (currentTheme) {
        AppTheme.FLUX_GLASS -> FluxGlassPalette.notesCard
        AppTheme.KINETIC_SPHERE -> KineticPalette.notesCard
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = if (isCustomGlass) Color.White else MaterialTheme.colorScheme.onSurface
    val subTextColor = if (isCustomGlass) Color.White.copy(alpha = 0.70f) else MaterialTheme.colorScheme.onSurfaceVariant
    val frame = NotesFrame(
        onBack, focusNoteId, onFocusNoteConsumed, viewModel, state, currentTheme, isCustomGlass, scope, density, listState,
        visualRows, holdingTransportSlot, backgroundColor, cardBg, textColor, subTextColor, launchDriveAuth, launchSignIn, pickAttachment
    )
    return NotesScope(frame, live).also { holder[0] = it }
}

@Composable
private fun rememberNotesLaunchers(
    live: NotesLive,
    holder: Array<NotesScope?>,
    scope: CoroutineScope
): Triple<() -> Unit, () -> Unit, () -> Unit> = with(live) {
    val launchDriveAuth = rememberGoogleDriveAuthLauncher { granted, errorMessage ->
        if (granted) {
            holder[0]!!.finishRelayGrant(pendingAcceptAfterRelay)
        } else {
            attachError = errorMessage ?: AppI18n.t("drive_relay_not_enabled")
            pendingAcceptAfterRelay = null
        }
    }

    val launchSignIn = rememberGoogleSignInLauncher { idToken, email, errorMessage ->
        if (idToken.isNullOrBlank()) {
            attachError = errorMessage ?: AppI18n.t("google_signin_cancelled")
            pendingAcceptAfterRelay = null
            return@rememberGoogleSignInLauncher
        }
        scope.launch {
            cancellableCatching {
                GoogleLinkCoordinator.linkWithGoogleIdToken(idToken, email)
            }.onSuccess {
                FileApexServices.settings.setCellularEnabled(true)
                if (GoogleDriveAuth.hasGrant()) {
                    holder[0]!!.finishRelayGrant(pendingAcceptAfterRelay)
                } else {
                    showDrivePermission = true
                }
            }.onFailure { error ->
                attachError = UserFacingErrors.message(error, "google_link_failed")
                pendingAcceptAfterRelay = null
            }
        }
    }

    fun decideAttachment(picked: PickedLocalFile) {
        when (val decision = DriveRelayPolicy.evaluateNotesAttachment(picked.sizeBytes)) {
            NotesAttachmentDecision.AllowLan,
            NotesAttachmentDecision.AllowRelay -> holder[0]!!.acceptPickedAttachment(picked)
            is NotesAttachmentDecision.OfferRelayOptIn -> {
                pendingRelayPick = picked
                relayOptIn = decision
            }
            is NotesAttachmentDecision.NeedsRelayEnabled -> {
                attachError = "${decision.fileLabel} is over the ${decision.lanLimitLabel} " +
                    "offline Notes limit. Enable Google Drive Relay in Settings → Cellular " +
                    "to send larger files."
            }
            is NotesAttachmentDecision.TooLargeForRelay -> {
                attachError = "${decision.fileLabel} is over the ${decision.relayLimitLabel} " +
                    "Google Drive Relay limit. Choose a smaller file or raise the Relay size " +
                    "limit in Settings → Cellular."
            }
            is NotesAttachmentDecision.TooLargeForLan -> {
                attachError = "${decision.fileLabel} is over the ${decision.lanLimitLabel} " +
                    "Notes attachment limit. Choose a smaller file."
            }
        }
    }

    // The app's own file screen, not the system picker. One file goes through the size checks and a caption;
    // several are sent one after the other like a drop.
    val pickAttachment = rememberFriendFilePicker(titleKey = "post_to_bulletin_board", viewModelKey = "bulletin-file-pick") { files ->
        scope.launch {
            val single = files.singleOrNull()
            if (single != null) {
                val picked = withContext(Dispatchers.IO) { PickedLocalFile(single.name, single.length(), single.absolutePath) }
                decideAttachment(picked)
            } else if (files.isNotEmpty()) {
                holder[0]!!.handleDroppedPaths(files.map { it.absolutePath }, null)
            }
        }
    }

    Triple(launchDriveAuth, launchSignIn, pickAttachment)
}
