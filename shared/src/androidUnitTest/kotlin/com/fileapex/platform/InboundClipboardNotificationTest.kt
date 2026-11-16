package com.fileapex.platform

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.fileapex.data.settings.initAndroidAppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboundClipboardNotificationTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun giveThePlatformItsContext() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        initAndroidAppSettings(application)
    }

    private fun tapped(text: String): PendingIntent {
        PlatformClipboard.applyRemoteText(text, "Mac")
        val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return Shadows.shadowOf(manager).allNotifications.last().contentIntent
    }

    @Test
    fun tappingAPushedMapLinkOpensItWithoutTheAppBeingOpen() {
        val pending = Shadows.shadowOf(tapped("https://www.google.com/maps/@37.7749,-122.4194,15z"))
        assertTrue("the tap starts an activity, not the copy broadcast", pending.isActivity)
        val intent: Intent = pending.savedIntents.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.data.toString().startsWith("http") || intent.data.toString().startsWith("geo:"))
    }

    @Test
    fun tappingAPushedVideoLinkOpensItWithItsStartTime() {
        val intent = Shadows.shadowOf(tapped("https://www.youtube.com/watch?v=abc&start=90")).savedIntents.single()
        assertEquals("https://www.youtube.com/watch?v=abc&t=90s", intent.data.toString())
    }

    @Test
    fun tappingPushedPlainTextStillCopiesIt() {
        val pending = Shadows.shadowOf(tapped("just some text"))
        assertTrue(pending.isBroadcast)
    }
}
