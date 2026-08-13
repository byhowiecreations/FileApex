package com.fileapex.platform

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNotificationChannelsTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun testEnsureInitializedCompletesWithoutThrowingWhenFGSNotificationActive() {
        val legacyChannel = NotificationChannel(
            AndroidNotificationChannels.SHARE_SERVER_ACTIVE,
            "FileApex Server Legacy",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(legacyChannel)

        val notification = Notification.Builder(context, AndroidNotificationChannels.SHARE_SERVER_ACTIVE)
            .setContentTitle("Server Active Test")
            .setContentText("Testing FGS active state")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        notificationManager.notify(1, notification)

        val activeNotifs = notificationManager.activeNotifications
        assertTrue(activeNotifs.any { it.notification.channelId == AndroidNotificationChannels.SHARE_SERVER_ACTIVE })

        AndroidNotificationChannels.migrateLegacyShareServerChannels(context)
        AndroidNotificationChannels.ensureShareServerChannel(context)

        val channel = notificationManager.getNotificationChannel(AndroidNotificationChannels.SHARE_SERVER_ACTIVE)
        assertNotNull(channel)

        // Verify migration was deferred and flag remained false while notification active
        val prefs = context.getSharedPreferences("fileapex_notification_channels", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("share_server_v2_channel_migrated", false))

        // Clear notification and run migration again — should now complete and set flag to true
        notificationManager.cancel(1)
        AndroidNotificationChannels.migrateLegacyShareServerChannels(context)
        assertTrue(prefs.getBoolean("share_server_v2_channel_migrated", false))
    }

    @Test
    fun testMigrationIsOneShotAndDoesNotRetryEveryStart() {
        AndroidNotificationChannels.migrateLegacyShareServerChannels(context)

        val prefs = context.getSharedPreferences("fileapex_notification_channels", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("share_server_v2_channel_migrated", false))

        AndroidNotificationChannels.migrateLegacyShareServerChannels(context)
    }
}

