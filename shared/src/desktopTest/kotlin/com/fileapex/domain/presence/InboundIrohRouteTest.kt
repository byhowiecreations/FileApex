package com.fileapex.domain.presence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InboundIrohRouteTest {
    private var now = 1_000_000L
    private val book = ReachabilityBook()
    private var lanStillWorks = true
    private val verified = mutableListOf<String>()
    private val route = InboundIrohRoute(book, clock = { now }, minGapMs = 30_000L) { id -> verified += id; lanStillWorks }

    @Test
    fun withNoRouteKnownTheRequestNamesIrohWithoutAnyCheck() = runBlocking {
        route.onInbound("A")
        assertEquals(mapOf("A" to RouteKind.Iroh), book.via.value)
        assertEquals(emptyList<String>(), verified)
    }

    @Test
    fun aLanLabelIsCheckedAndKeptWhileTheLanStillAnswers() = runBlocking {
        book.noteRoute("A", RouteKind.Lan)
        route.onInbound("A")
        assertEquals(listOf("A"), verified)
        assertEquals(mapOf("A" to RouteKind.Lan), book.via.value)
    }

    @Test
    fun aLanLabelThatNoLongerAnswersBecomesIroh() = runBlocking {
        book.noteRoute("A", RouteKind.Lan)
        lanStillWorks = false
        route.onInbound("A")
        assertEquals(mapOf("A" to RouteKind.Iroh), book.via.value)
    }

    @Test
    fun theLanIsCheckedAtMostOncePerPeerPerGap() = runBlocking {
        book.noteRoute("A", RouteKind.Lan)
        repeat(5) { route.onInbound("A") }
        assertEquals(1, verified.size)
        now += 30_000L
        route.onInbound("A")
        assertEquals(2, verified.size)
    }

    @Test
    fun anIrohOrTailscaleLabelIsNeverCheckedOrChanged() = runBlocking {
        book.noteRoute("A", RouteKind.Iroh)
        book.noteRoute("B", RouteKind.Tailnet)
        route.onInbound("A"); route.onInbound("B")
        assertEquals(emptyList<String>(), verified)
        assertEquals(mapOf("A" to RouteKind.Iroh, "B" to RouteKind.Tailnet), book.via.value)
    }
}
