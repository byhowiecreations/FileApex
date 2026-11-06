package com.fileapex.drop

import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** One time-limit choice on the share screen. */
class GuestDropLimit(val labelKey: String, val ttlMs: Long)

object GuestDropLimits {
    val choices = listOf(
        GuestDropLimit("share_link_15m", 15 * 60_000L),
        GuestDropLimit("share_link_1h", GuestDrops.DEFAULT_TTL_MS),
        GuestDropLimit("share_link_6h", 6 * 3_600_000L),
        GuestDropLimit("share_link_24h", GuestDrops.MAX_TTL_MS)
    )
    val default: GuestDropLimit = choices[1]
}

/** "45 s", "12 min", "1 h 5 min": what is left of a link, rounded down. */
fun timeLeftText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} min"
        s >= 60 -> "${s / 60} min"
        else -> "$s s"
    }
}

/** Translation keys the share screen uses, so a test can check that every language has them. */
object GuestDropStrings {
    val keys = listOf(
        "share_link_action", "share_link_title", "share_link_limit", "share_link_create", "share_link_creating", "share_link_note",
        "share_link_copy", "share_link_copied", "share_link_share", "share_link_stop", "share_link_expires",
        "share_link_closed", "share_link_message", "share_link_fail_unavailable", "share_link_fail_file", "share_link_fail_relay",
        "share_link_fail_many", "share_link_fail_start", "share_link_open_links", "share_link_downloading",
        "share_link_fail_consent", "share_link_consent_title", "share_link_consent_body", "share_link_consent_accept",
        "share_link_consent_decline", "share_link_page_title", "share_link_page_switch", "share_link_page_on", "share_link_page_off",
        "share_link_page_unasked", "share_link_page_about"
    )

    fun forFailure(reason: ShareFailure): String = when (reason) {
        ShareFailure.NotAvailable -> "share_link_fail_unavailable"
        ShareFailure.NotAFile -> "share_link_fail_file"
        ShareFailure.NoRelay -> "share_link_fail_relay"
        ShareFailure.TooMany -> "share_link_fail_many"
        ShareFailure.CouldNotStart -> "share_link_fail_start"
        ShareFailure.NotAccepted -> "share_link_fail_consent"
    }
}

/** The one place the app makes links: one on Android, three on a computer. */
object GuestDropHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set by the Android app: starts or stops the foreground service that keeps the process alive while links are open. */
    @Volatile
    var onNotice: (GuestDropNotice) -> Unit = {}

    val service: GuestDropService by lazy {
        GuestDropService(
            { embeddedGuestDropEngine() }, scope, GuestDrops(maxLive = if (currentPlatformLabel() == "Android") 1 else 3),
            allowed = { consent().allowed }
        )
            .also { GuestDropNoticeWatcher(scope, it.active) { notice -> onNotice(notice) } }
    }

    /** Ends every link. Runs on the hub's own scope, so it finishes even when the caller (a notification action) goes away at once. */
    fun stopAll(): Job = scope.launch { service.revokeAll() }

    /** The owner's current answer about relay use; the action and every link depend on it. */
    fun consent(): GuestLinkConsent = GuestLinkConsent.fromStored(FileApexServices.settings.guestLinkConsent.value)

    /** False in builds without Iroh, which hide the action. */
    val available: Boolean get() = embeddedGuestDropEngine() != null
}
