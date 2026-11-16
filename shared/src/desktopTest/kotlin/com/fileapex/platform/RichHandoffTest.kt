package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RichHandoffTest {
    // --- maps ---

    @Test
    fun aGoogleMapsViewportLinkGivesItsCoordinates() {
        val target = RichHandoff.mapTarget("https://www.google.com/maps/@37.7749,-122.4194,15z")!!
        assertEquals(37.7749, target.latitude!!, 0.0)
        assertEquals(-122.4194, target.longitude!!, 0.0)
        assertNull(target.label)
    }

    @Test
    fun aGoogleMapsPlaceLinkGivesItsNameAndCoordinates() {
        val target = RichHandoff.mapTarget("https://www.google.com/maps/place/Golden+Gate+Bridge/@37.8199,-122.4783,17z/data=!3m1!4b1")!!
        assertEquals("Golden Gate Bridge", target.label)
        assertEquals(37.8199, target.latitude!!, 0.0)
        assertEquals(-122.4783, target.longitude!!, 0.0)
    }

    @Test
    fun theOtherGoogleMapsShapesAreUnderstood() {
        assertEquals(48.8584, RichHandoff.mapTarget("https://maps.google.com/?q=48.8584,2.2945")!!.latitude!!, 0.0)
        assertEquals(2.2945, RichHandoff.mapTarget("https://www.google.com/maps/search/?api=1&query=48.8584,2.2945")!!.longitude!!, 0.0)
        assertEquals(40.0, RichHandoff.mapTarget("https://www.google.com/maps?ll=40.0,-3.5")!!.latitude!!, 0.0)
    }

    @Test
    fun aGoogleMapsSearchWithoutCoordinatesKeepsTheQuery() {
        val target = RichHandoff.mapTarget("https://www.google.com/maps?q=Eiffel+Tower")!!
        assertNull(target.latitude)
        assertEquals("Eiffel Tower", target.label)
    }

    @Test
    fun anAppleMapsLinkGivesCoordinatesAndName() {
        val target = RichHandoff.mapTarget("https://maps.apple.com/?ll=37.7749,-122.4194&q=Home")!!
        assertEquals(37.7749, target.latitude!!, 0.0)
        assertEquals("Home", target.label)
    }

    @Test
    fun anOpenStreetMapLinkGivesCoordinatesFromTheFragmentOrTheMarker() {
        assertEquals(-0.1278, RichHandoff.mapTarget("https://www.openstreetmap.org/#map=17/51.5074/-0.1278")!!.longitude!!, 0.0)
        assertEquals(51.5, RichHandoff.mapTarget("https://www.openstreetmap.org/?mlat=51.5&mlon=-0.12#map=16/52.0/-0.1")!!.latitude!!, 0.0)
    }

    @Test
    fun otherLinksAndShortMapLinksAreLeftAlone() {
        assertNull(RichHandoff.mapTarget("https://www.google.com/search?q=maps"))
        assertNull(RichHandoff.mapTarget("https://example.com/maps/@1.0,2.0,3z"))
        assertNull("a short link cannot be resolved without the network; Android opens it natively", RichHandoff.mapTarget("https://maps.app.goo.gl/abc123"))
        assertNull(RichHandoff.mapTarget("not a url"))
        assertNull(RichHandoff.mapTarget("https://www.google.com/maps/@999,-122.4194,15z"))
    }

    @Test
    fun theGeoUriPinsTheCoordinatesAndEncodesTheLabel() {
        assertEquals(
            "geo:37.8199,-122.4783?q=37.8199,-122.4783(Golden%20Gate%20Bridge)",
            RichHandoff.geoUri(RichHandoff.mapTarget("https://www.google.com/maps/place/Golden+Gate+Bridge/@37.8199,-122.4783,17z")!!)
        )
        assertEquals("geo:37.7749,-122.4194?q=37.7749,-122.4194", RichHandoff.geoUri(RichHandoff.mapTarget("https://www.google.com/maps/@37.7749,-122.4194,15z")!!))
        assertEquals("geo:0,0?q=Eiffel%20Tower", RichHandoff.geoUri(RichHandoff.mapTarget("https://www.google.com/maps?q=Eiffel+Tower")!!))
        assertEquals("geo:1.5,2.5?q=1.5,2.5(A%20%28b%29)", RichHandoff.geoUri(RichHandoff.MapTarget(1.5, 2.5, "A (b)")))
    }

    // --- video timestamps ---

    @Test
    fun theTimestampIsReadFromEveryFormOfAVideoLink() {
        assertEquals(90, RichHandoff.videoTimestampSeconds("https://www.youtube.com/watch?v=abc&t=90s"))
        assertEquals(90, RichHandoff.videoTimestampSeconds("https://www.youtube.com/watch?v=abc&t=1m30s"))
        assertEquals(3723, RichHandoff.videoTimestampSeconds("https://youtu.be/abc?t=1h2m3s"))
        assertEquals(90, RichHandoff.videoTimestampSeconds("https://youtu.be/abc?t=90"))
        assertEquals(45, RichHandoff.videoTimestampSeconds("https://www.youtube.com/watch?v=abc&start=45"))
        assertEquals(120, RichHandoff.videoTimestampSeconds("https://vimeo.com/123456#t=2m"))
        assertEquals(75, RichHandoff.videoTimestampSeconds("https://www.twitch.tv/videos/987654?t=1m15s"))
        assertNull(RichHandoff.videoTimestampSeconds("https://www.youtube.com/watch?v=abc"))
        assertNull(RichHandoff.videoTimestampSeconds("https://example.com/page?t=90"))
    }

    @Test
    fun aLinkIsWrittenWithTheFormTheSiteUnderstands() {
        assertEquals("https://www.youtube.com/watch?v=abc&t=90s", RichHandoff.canonicalVideoUrl("https://www.youtube.com/watch?v=abc&start=90"))
        assertEquals("https://www.youtube.com/watch?v=abc&t=90s", RichHandoff.canonicalVideoUrl("https://www.youtube.com/watch?v=abc&t=1m30s"))
        assertEquals("https://youtu.be/abc?si=xyz&t=75s", RichHandoff.canonicalVideoUrl("https://youtu.be/abc?si=xyz&t=75"))
        assertEquals("https://vimeo.com/123456#t=120s", RichHandoff.canonicalVideoUrl("https://vimeo.com/123456#t=2m"))
        assertEquals("https://www.twitch.tv/videos/987654?t=1h1m1s", RichHandoff.canonicalVideoUrl("https://www.twitch.tv/videos/987654?t=3661"))
    }

    @Test
    fun aLinkWithoutATimestampOrNotAVideoIsUnchanged() {
        assertEquals("https://www.youtube.com/watch?v=abc", RichHandoff.canonicalVideoUrl("https://www.youtube.com/watch?v=abc"))
        assertEquals("https://example.com/page?t=90", RichHandoff.canonicalVideoUrl("https://example.com/page?t=90"))
        assertEquals("not a url", RichHandoff.canonicalVideoUrl("not a url"))
    }

    @Test
    fun aPlaybackPositionIsAddedToAVideoLink() {
        assertEquals("https://www.youtube.com/watch?v=abc&t=95s", RichHandoff.withVideoTimestamp("https://www.youtube.com/watch?v=abc", 95))
        assertEquals("https://youtu.be/abc?t=95s", RichHandoff.withVideoTimestamp("https://youtu.be/abc", 95))
        assertEquals("https://www.youtube.com/watch?v=abc&t=95s", RichHandoff.withVideoTimestamp("https://www.youtube.com/watch?v=abc&t=10", 95))
        assertEquals("https://vimeo.com/123456#t=95s", RichHandoff.withVideoTimestamp("https://vimeo.com/123456", 95))
        assertEquals("https://www.twitch.tv/videos/987654?t=1m35s", RichHandoff.withVideoTimestamp("https://www.twitch.tv/videos/987654", 95))
    }

    @Test
    fun aTinyOrUnusablePositionOrAnotherSiteLeavesTheLinkAlone() {
        assertEquals("https://www.youtube.com/watch?v=abc", RichHandoff.withVideoTimestamp("https://www.youtube.com/watch?v=abc", 0))
        assertEquals("https://www.youtube.com/watch?v=abc", RichHandoff.withVideoTimestamp("https://www.youtube.com/watch?v=abc", 2))
        assertEquals("https://example.com/v", RichHandoff.withVideoTimestamp("https://example.com/v", 95))
        assertEquals("https://www.youtube.com/shorts/abc", RichHandoff.withVideoTimestamp("https://www.youtube.com/shorts/abc", 95))
    }

    // --- what a tap on a link opens ---

    @Test
    fun aMapLinkOpensAsGeoWhenThePhoneHasAMapsAppAndAsItsLinkOtherwise() {
        val link = "https://maps.apple.com/?ll=37.7749,-122.4194&q=Home"
        assertEquals("geo:37.7749,-122.4194?q=37.7749,-122.4194(Home)", RichHandoff.openTarget(link, canOpenGeo = true))
        assertEquals(link, RichHandoff.openTarget(link, canOpenGeo = false))
    }

    @Test
    fun aVideoLinkOpensWithItsStartTimeAndAnythingElseAsItWas() {
        assertEquals("https://www.youtube.com/watch?v=abc&t=90s", RichHandoff.openTarget("https://www.youtube.com/watch?v=abc&start=90", canOpenGeo = true))
        assertEquals("https://example.com/page", RichHandoff.openTarget("https://example.com/page", canOpenGeo = true))
    }
}
