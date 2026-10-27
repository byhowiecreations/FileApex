package com.fileapex.friends

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
