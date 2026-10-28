package com.fileapex.notice

import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.di.FileApexServices
import com.fileapex.update.FileApexSemVer
import com.fileapex.update.currentAppVersionName
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches `notice.json`, keeps the last accepted file and exposes the notices that apply to this install. It is started
 * on app launch and when the app returns to the foreground; there is no timer.
 */
object NoticeCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val store: NoticeSettingsStore get() = FileApexServices.settings.noticeSettings
    private val transport by lazy { createNoticeTransport(NoticeUrlPolicy.forUrl(noticeUrl())) }

    fun noticeUrl(): String = GeneratedNoticeConfig.NOTICE_URL.ifBlank { NoticeSync.OFFICIAL_URL }

    val notices: StateFlow<List<VisibleNotice>> by lazy {
        val settings = FileApexServices.settings
        combine(
            store.enabled,
            store.revision,
            settings.googleAccountLinkEnabled,
            settings.tailscaleEnabled,
            settings.irohEnabled
        ) { enabled, _, google, tailscale, iroh ->
            if (!enabled) emptyList() else visibleNow(featuresOf(google, tailscale, iroh))
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    fun onAppLaunch() = launchCheck(manual = false)

    fun onForeground() = launchCheck(manual = false)

    /** The explanation was answered or dismissed: from now on checking is allowed. */
    fun onChoiceMade() = launchCheck(manual = true)

    fun checkNow() = launchCheck(manual = true)

    /** The Settings switch: choosing either way counts as having seen the explanation, and turning it on checks at once. */
    fun setEnabledByUser(enabled: Boolean) {
        store.setEnabled(enabled)
        store.markChoiceMade()
        if (enabled) checkNow()
    }

    fun dismiss(id: String) {
        val notice = notices.value.firstOrNull { it.notice.id == id }?.notice ?: return
        if (NoticeIndicators.canDismiss(notice)) {
            store.dismiss(id, TimeUtils.now(), forgetSeen = notice.severity == NoticeSeverity.Important)
        }
    }

    fun markListingSeen() = store.markSeen(NoticeIndicators.idsSeenByListing(notices.value))

    fun markOpened(id: String) = store.markSeen(listOf(id))

    fun linkFor(notice: Notice): String? = NoticeIndicators.linkFor(notice, channel())

    fun updateTarget(): NoticeUpdateTarget = NoticeIndicators.updateTarget(channel())

    private fun launchCheck(manual: Boolean) {
        scope.launch {
            mutex.withLock {
                val outcome = NoticeSync.check(store, transport, noticeUrl(), noticeTrustedKeys(), TimeUtils.now(), manual)
                if (outcome != NoticeSyncOutcome.Skipped && outcome != NoticeSyncOutcome.Throttled) println("NoticeCoordinator: $outcome")
            }
        }
    }

    private fun featuresOf(google: Boolean, tailscale: Boolean, iroh: Boolean): Set<NoticeFeature> = buildSet {
        if (google) add(NoticeFeature.GoogleLink)
        if (tailscale) add(NoticeFeature.Tailscale)
        if (iroh) add(NoticeFeature.Iroh)
    }

    private fun visibleNow(features: Set<NoticeFeature>): List<VisibleNotice> {
        val file = store.cachedFile() ?: return emptyList()
        val read = NoticeFileReader.read(file, noticeTrustedKeys(), 0L) as? NoticeReadResult.Accepted ?: return emptyList()
        val now = TimeUtils.now()
        val context = NoticeContext(FileApexSemVer.parse(currentAppVersionName()), platform(), channel(), features, now)
        val dismissed = store.dismissed()
        val seen = store.seen()
        return NoticeSelector.applicable(read.payload, context)
            .filterNot { NoticeIndicators.isHiddenByDismissal(it, dismissed[it.id], now) }
            .map { VisibleNotice(it, it.id in seen) }
    }

    private fun platform(): NoticePlatform = when (currentPlatformLabel()) {
        "Android" -> NoticePlatform.Android
        "Mac" -> NoticePlatform.Macos
        "Windows" -> NoticePlatform.Windows
        else -> NoticePlatform.Linux
    }

    private fun channel(): NoticeChannel = when {
        FileApexServices.isPlayStoreBuild -> NoticeChannel.Play
        FileApexServices.isFdroidBuild -> NoticeChannel.Fdroid
        else -> NoticeChannel.Github
    }
}
