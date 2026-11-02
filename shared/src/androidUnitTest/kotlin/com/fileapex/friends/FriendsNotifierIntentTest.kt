package com.fileapex.friends

import android.app.Application
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import java.io.File
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferDirection
import com.fileapex.friends.transfer.FriendTransferStatus
import org.robolectric.Shadows
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The receiver is not exported and has no intent filter, so a button on a notification only works when its intent names
 * the receiver; an action-only intent reaches nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FriendsNotifierIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun everyOfferButtonNamesTheReceiver() {
        listOf(FRIENDS_ACTION_ACCEPT, FRIENDS_ACTION_DECLINE, FRIENDS_ACTION_ACCEPT_ALWAYS).forEach { action ->
            val intent = friendsActionIntent(context, action, FRIENDS_EXTRA_OFFER_ID, "offer-1")
            assertEquals(action, intent.action)
            assertEquals(FriendsOfferActionReceiver::class.java.name, intent.component?.className)
            assertEquals("offer-1", intent.getStringExtra(FRIENDS_EXTRA_OFFER_ID))
        }
    }

    @Test
    fun theCancelButtonNamesTheReceiver() {
        val intent = friendsActionIntent(context, FRIENDS_ACTION_CANCEL_TRANSFER, FRIENDS_EXTRA_TRANSFER_ID, "t-1")
        assertNotNull(intent.component)
        assertEquals("t-1", intent.getStringExtra(FRIENDS_EXTRA_TRANSFER_ID))
    }

    @Test fun collidingOfferHashesDoNotReplaceAnotherButtonsTarget() {
        val firstId = "Aa"
        val secondId = "BB"
        assertEquals(firstId.hashCode(), secondId.hashCode())
        val first = friendsActionIntent(context, FRIENDS_ACTION_ACCEPT, FRIENDS_EXTRA_OFFER_ID, firstId)
        val second = friendsActionIntent(context, FRIENDS_ACTION_ACCEPT, FRIENDS_EXTRA_OFFER_ID, secondId)
        assertFalse(first.filterEquals(second))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val firstButton = PendingIntent.getBroadcast(context, 1, first, flags)
        val secondButton = PendingIntent.getBroadcast(context, 1, second, flags)
        assertFalse(firstButton == secondButton)
    }

    @Test @Config(shadows = [NotificationBitmaps::class]) fun collidingOffersStayVisibleAndResolveIndependently() {
        val manager = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(manager).setNotificationsEnabled(true)
        initAndroidFriendsNotifier(context)
        FriendsNotifier.offerPending(PendingFriendOffer("Aa", "Alex", "first.txt", 10))
        FriendsNotifier.offerPending(PendingFriendOffer("BB", "Blair", "second.txt", 10))
        assertEquals(2, manager.activeNotifications.size)
        FriendsNotifier.offerResolved("Aa")
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.single().notification.extras.getCharSequence("android.text").toString().contains("second.txt"))
    }

    @Implements(BitmapFactory::class)
    class NotificationBitmaps {
        companion object {
            @JvmStatic @Implementation
            fun decodeResource(resources: Resources, id: Int): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    @Test fun collidingTransfersStayVisibleAndFinishIndependently() {
        val manager = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(manager).setNotificationsEnabled(true)
        initAndroidFriendsNotifier(context)
        val first = FriendTransfer("Aa", "friend", "Alex", FriendTransferDirection.SEND, "first.txt", 10, status = FriendTransferStatus.ACTIVE)
        val second = first.copy(id = "BB", fileName = "second.txt")
        FriendsNotifier.transferChanged(first)
        FriendsNotifier.transferChanged(second)
        assertEquals(2, manager.activeNotifications.size)
        FriendsNotifier.transferChanged(first.copy(status = FriendTransferStatus.DONE))
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.single().notification.extras.getCharSequence("android.text").toString().contains("second.txt"))
    }

    @Test fun anUnknownExplicitActionCannotAcceptAnOffer() {
        val owner = FriendsHub::class.java.getDeclaredField("offers").apply { isAccessible = true }.get(FriendsHub)
        @Suppress("UNCHECKED_CAST")
        val decisions = owner.javaClass.getDeclaredField("deciders").apply { isAccessible = true }.get(owner) as MutableMap<String, CompletableDeferred<Boolean>>
        val decision = CompletableDeferred<Boolean>()
        val id = "unknown-action-test"
        decisions[id] = decision
        try {
            FriendsOfferActionReceiver().onReceive(context, friendsActionIntent(context, "unrelated.action", FRIENDS_EXTRA_OFFER_ID, id))
            assertFalse("Only the declared Accept/Decline actions may settle an offer", decision.isCompleted)
        } finally { decisions.remove(id); decision.cancel() }
    }

    @Test fun receivedFolderIntentsForCollidingPathsHaveIndependentIdentity() {
        val firstPath = "/tmp/Aa/report.txt"
        val secondPath = "/tmp/BB/report.txt"
        assertEquals(firstPath.hashCode(), secondPath.hashCode())
        fun launch() = Intent(Intent.ACTION_MAIN).setClassName(context.packageName, "com.fileapex.MainActivity")
        val first = launch().withFriendsFolder(File(firstPath))
        val second = launch().withFriendsFolder(File(secondPath))
        assertFalse(first.filterEquals(second))
        assertEquals("/tmp/Aa", first.getStringExtra(EXTRA_OPEN_LOCAL_FOLDER))
        assertEquals("/tmp/BB", second.getStringExtra(EXTRA_OPEN_LOCAL_FOLDER))
    }

    @Test @Config(shadows = [NotificationBitmaps::class]) fun completedFilesWithCollidingPathsKeepBothNotifications() {
        val manager = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(manager).setNotificationsEnabled(true)
        initAndroidFriendsNotifier(context)
        val field = FriendsNotifier::class.java.getDeclaredField("completionEnabled").apply { isAccessible = true }
        val previous = field.get(FriendsNotifier)
        try {
            field.set(FriendsNotifier, { true })
            FriendsNotifier.fileReceived(FriendReceivedFile("Alex", "first.txt", "/tmp/Aa/report.txt"))
            FriendsNotifier.fileReceived(FriendReceivedFile("Blair", "second.txt", "/tmp/BB/report.txt"))
            assertEquals(2, manager.activeNotifications.size)
        } finally { field.set(FriendsNotifier, previous) }
    }
}
