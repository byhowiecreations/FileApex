package com.fileapex.friends

import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.PresenceForegroundRefresh
import com.fileapex.friends.ui.FriendsQueueUi
import com.fileapex.platform.MacFriendNotificationSink
import com.fileapex.platform.SerialDesktopNotificationSink
import com.fileapex.platform.LinuxNotificationDelivery
import com.fileapex.platform.WindowsNotificationDelivery
import com.fileapex.platform.DesktopPlatformPaths
import com.fileapex.platform.revealInFolder
import javax.swing.SwingUtilities

@Volatile internal var desktopFriendNotifications: DesktopFriendNotifications? = null
private var macSink: MacFriendNotificationSink? = null
private var serialSink: SerialDesktopNotificationSink? = null

internal fun installDesktopFriendsNotifications(showWindow: () -> Unit) {
    if (desktopFriendNotifications != null) return
    val sink: DesktopFriendNotificationSink = when {
        DesktopPlatformPaths.isMacOs() -> MacFriendNotificationSink.load()?.also { macSink = it } ?: return
        DesktopPlatformPaths.isWindows() -> SerialDesktopNotificationSink(WindowsNotificationDelivery()).also { serialSink = it }
        DesktopPlatformPaths.isLinux() -> SerialDesktopNotificationSink(LinuxNotificationDelivery()).also { serialSink = it }
        else -> return
    }
    desktopFriendNotifications = DesktopFriendNotifications(
        sink, { PresenceForegroundRefresh.isForeground.value },
        { FileApexServices.settings.fileTransferNotificationsEnabled.value },
        { id -> FriendsHub.pendingOffers.value.any { it.id == id } },
        { SwingUtilities.invokeLater { FriendsQueueUi.expanded.value = true; showWindow() } },
        { path -> SwingUtilities.invokeLater { revealInFolder(path); showWindow() } }
    )
}

internal fun disposeDesktopFriendsNotifications() {
    desktopFriendNotifications = null
    serialSink?.dispose(); serialSink = null
    macSink?.dispose(); macSink = null
}

actual object FriendsNotifier {
    actual val canRequestPermission: Boolean get() = macSink != null
    actual fun requestPermission() { macSink?.requestPermission() }
    actual fun offerPending(offer: PendingFriendOffer) { desktopFriendNotifications?.offerPending(offer) }
    actual fun offerResolved(offerId: String) { desktopFriendNotifications?.offerResolved(offerId) }
    actual fun fileReceived(file: FriendReceivedFile) { desktopFriendNotifications?.fileReceived(file) }
    actual fun transferChanged(transfer: com.fileapex.friends.transfer.FriendTransfer) = Unit
}
