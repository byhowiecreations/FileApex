package com.fileapex.drop.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fileapex.di.FileApexServices
import com.fileapex.drop.GuestDropHub
import com.fileapex.drop.GuestLinkConsent
import com.fileapex.i18n.stringRes

/** The owner's saved answer, read so the screen follows a change made on the Settings page. */
@Composable
private fun guestLinkConsent(): GuestLinkConsent =
    GuestLinkConsent.fromStored(FileApexServices.settings.guestLinkConsent.collectAsState().value)

/** True while "Share as link" may be shown: in a build that has it, and not after a decline. */
@Composable
fun guestLinkOffered(): Boolean = GuestDropHub.available && guestLinkConsent().offered

/** What the Settings row says: on, off after a decline, or not asked yet. */
@Composable
fun shareLinkRowSubtitle(): String = stringRes(
    when (guestLinkConsent()) {
        GuestLinkConsent.Accepted -> "share_link_page_on"
        GuestLinkConsent.Declined -> "share_link_page_off"
        GuestLinkConsent.Unasked -> "share_link_page_unasked"
    }
)

/** The question that comes before the first link. Leaving it (outside tap or back) answers nothing. */
@Composable
fun GuestLinkConsentDialog(onDecision: (accepted: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("share_link_consent_title")) },
        text = { Text(stringRes("share_link_consent_body")) },
        confirmButton = { Button(onClick = { onDecision(true) }) { Text(stringRes("share_link_consent_accept")) } },
        dismissButton = { TextButton(onClick = { onDecision(false) }) { Text(stringRes("share_link_consent_decline")) } }
    )
}
