package com.fileapex.platform

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.fileapex.data.settings.initAndroidAppSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenUrlIntentTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun giveThePlatformItsContext() {
        initAndroidAppSettings(application)
    }

    private fun opened(url: String): Intent? {
        PlatformClipboard.openUrlInDefaultBrowser(url)
        ShadowLooper.idleMainLooper()
        return Shadows.shadowOf(application).nextStartedActivity
    }

    @Test
    fun aGoogleMapsLinkOpensThePhonesMapsAppThroughAGeoUri() {
        val intent = opened("https://www.google.com/maps/place/Golden+Gate+Bridge/@37.8199,-122.4783,17z")!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo:37.8199,-122.4783?q=37.8199,-122.4783(Golden%20Gate%20Bridge)", intent.data.toString())
    }

    @Test
    fun anAppleMapsLinkIsHandedToTheMapsAppToo() {
        assertEquals("geo:37.7749,-122.4194?q=37.7749,-122.4194(Home)", opened("https://maps.apple.com/?ll=37.7749,-122.4194&q=Home")!!.data.toString())
    }

    @Test
    fun aVideoLinkOpensWithItsStartTimeInTheFormTheSiteReads() {
        assertEquals("https://www.youtube.com/watch?v=abc&t=90s", opened("https://www.youtube.com/watch?v=abc&start=90")!!.data.toString())
    }

    @Test
    fun anOrdinaryLinkOpensAsItWas() {
        assertEquals("https://example.com/page?x=1", opened("https://example.com/page?x=1")!!.data.toString())
    }
}
