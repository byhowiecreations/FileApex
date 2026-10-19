package com.fileapex.domain.presence

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerProbeSchedulerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun aSlowDeviceNeverDelaysAnother() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val fastDone = CompletableDeferred<Unit>()
        val scheduler = PeerProbeScheduler(scope) { id, _ ->
            if (id == "dead") release.await() else fastDone.complete(Unit)
        }
        scheduler.poke("dead", PresenceMode.Full)
        scheduler.poke("alive", PresenceMode.Full)
        withTimeout(2_000) { fastDone.await() }
        assertTrue(scheduler.isRunning("dead"))
        release.complete(Unit)
    }

    @Test
    fun aPokeWhileRunningRunsOnceMoreWithTheMostThoroughMode() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val modes = CopyOnWriteArrayList<PresenceMode>()
        val second = CompletableDeferred<Unit>()
        val scheduler = PeerProbeScheduler(scope) { _, mode ->
            modes += mode
            if (modes.size == 1) gate.await() else second.complete(Unit)
        }
        scheduler.poke("a", PresenceMode.Light)
        while (modes.isEmpty()) kotlinx.coroutines.delay(5)
        scheduler.poke("a", PresenceMode.Full)
        scheduler.poke("a", PresenceMode.Light)
        gate.complete(Unit)
        withTimeout(2_000) { second.await() }
        assertEquals(listOf(PresenceMode.Light, PresenceMode.Full), modes.toList())
    }

    @Test
    fun aLightPokeNeverDowngradesAFullOne() {
        assertEquals(PresenceMode.Full, PresenceMode.Full.merge(PresenceMode.Light))
        assertEquals(PresenceMode.Full, PresenceMode.Light.merge(PresenceMode.Full))
        assertEquals(PresenceMode.Light, PresenceMode.Light.merge(PresenceMode.Light))
    }

    @Test
    fun aFailingDeviceDoesNotStopTheNextPoke() = runBlocking<Unit> {
        val ok = CompletableDeferred<Unit>()
        var calls = 0
        val scheduler = PeerProbeScheduler(scope) { _, _ ->
            calls++
            if (calls == 1) error("boom") else ok.complete(Unit)
        }
        scheduler.poke("a", PresenceMode.Light)
        while (calls == 0) kotlinx.coroutines.delay(5)
        kotlinx.coroutines.delay(50)
        scheduler.poke("a", PresenceMode.Light)
        withTimeout(2_000) { ok.await() }
    }
}
