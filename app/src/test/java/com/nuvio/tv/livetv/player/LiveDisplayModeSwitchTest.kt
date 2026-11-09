package com.nuvio.tv.livetv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic window traces: these do not emulate an HDMI display or measure physical flicker. */
class LiveDisplayModeSwitchTest {
    @Test
    fun `already active timing under an unlisted mode id never writes or pauses`() = runBlocking {
        val current = mode(7, 59.94f)
        val port = Port(current, listOf(mode(3, 59.94f), mode(4, 60f)))
        val clock = Clock()
        val switch = switch(port, clock)
        switch.activate()

        repeat(20) { channel ->
            assertEquals(LiveDisplayModeResult.ALREADY_MATCHED, switch.match(59.94f, channel).result)
        }

        assertTrue(port.writes.isEmpty())
        assertTrue(port.leases.isEmpty())
        assertTrue(clock.waits.isEmpty())
    }

    @Test
    fun `real fractional to integer changes request once and require three stable polls`() = runBlocking {
        for ((fractional, integer) in listOf(59.94f to 60f, 23.976f to 24f, 29.97f to 30f)) {
            val current = mode(1, fractional)
            val target = mode(2, integer)
            val port = Port(current, listOf(current, target))
            port.onWrite = { port.current = target }
            val clock = Clock()
            val requests = mutableListOf<Boolean>()
            val switch = switch(port, clock, requests = requests)
            switch.activate()

            val outcome = switch.match(integer, "channel")

            assertEquals("$fractional -> $integer", LiveDisplayModeResult.APPLIED, outcome.result)
            assertEquals(target, outcome.target)
            assertEquals(listOf(2), port.writes)
            assertEquals(listOf(false), requests)
            assertEquals(listOf(100L, 100L, 100L), clock.waits)
            assertEquals(300L, clock.now)
            assertEquals(listOf(1), port.leases.map { it.closeCount })
        }
    }

    @Test
    fun `fractional timing cannot falsely complete an integer request`() = runBlocking {
        for ((fractional, integer) in listOf(23.976f to 24f, 29.97f to 30f, 59.94f to 60f)) {
            val current = mode(1, fractional)
            val port = Port(current, listOf(current, mode(2, integer)))
            val clock = Clock()
            val switch = switch(port, clock)
            switch.activate()

            assertEquals(LiveDisplayModeResult.NOT_APPLIED, switch.match(integer, "channel").result)
            assertEquals(listOf(2, 2), port.writes)
            assertEquals(7_000L, clock.now)
            assertEquals(70, clock.waits.size)
            assertEquals(listOf(1), port.leases.map { it.closeCount })
        }
    }

    @Test
    fun `equivalent observed timing can complete when the driver reports another mode id`() = runBlocking {
        val current = mode(1, 50f)
        val target = mode(2, 59.94f)
        val alias = mode(9, 59.94f)
        val port = Port(current, listOf(current, target))
        port.onWrite = { port.current = alias }
        val clock = Clock()
        val switch = switch(port, clock)
        switch.activate()

        val outcome = switch.match(59.94f, "channel")

        assertEquals(LiveDisplayModeResult.APPLIED, outcome.result)
        assertEquals(alias, outcome.current)
        assertEquals(listOf(2), port.writes)
        assertEquals(3, clock.waits.size)
    }

    @Test
    fun `a missed poll resets the three consecutive stable samples`() = runBlocking {
        val current = mode(1, 59.94f)
        val target = mode(2, 60f)
        val port = Port(current, listOf(current, target))
        val clock = Clock()
        val switch = switch(port, clock, wait = { millis ->
            clock.advance(millis)
            port.current = if (clock.waits.size == 3) current else target
        })
        switch.activate()

        assertEquals(LiveDisplayModeResult.APPLIED, switch.match(60f, "channel").result)
        assertEquals(6, clock.waits.size)
        assertEquals(listOf(2), port.writes)
    }

    @Test
    fun `target arriving at the retry deadline gets stable confirmation without another request`() = runBlocking {
        for (arrivesAtMs in listOf(3_900L, 4_000L)) {
            val current = mode(1, 60f)
            val target = mode(2, 50f)
            val port = Port(current, listOf(current, target))
            val clock = Clock()
            val requests = mutableListOf<Boolean>()
            val switch = switch(port, clock, wait = { millis ->
                clock.advance(millis)
                port.current = if (clock.now >= arrivesAtMs) target else current
            }, requests = requests)
            switch.activate()

            assertEquals(LiveDisplayModeResult.APPLIED, switch.match(25f, "channel").result)
            assertEquals(arrivesAtMs + 200L, clock.now)
            assertEquals(listOf(2), port.writes)
            assertEquals(listOf(false), requests)
            assertEquals(listOf(1), port.leases.map { it.closeCount })
        }
    }

    @Test
    fun `channel changes share one pending initial request and one retry budget`() = runBlocking {
        val current = mode(1, 60f)
        val target = mode(2, 50f)
        val port = Port(current, listOf(current, target))
        val clock = Clock()
        val waits = PendingWait(clock)
        val requests = mutableListOf<Boolean>()
        val switch = switch(port, clock, waits::wait, requests)
        switch.activate()

        val first = async(start = CoroutineStart.UNDISPATCHED) { switch.match(25f, "A") }
        waits.advance(1_000L)
        first.cancelAndJoin()
        val second = async(start = CoroutineStart.UNDISPATCHED) { switch.match(25f, "B") }
        assertEquals(listOf(2), port.writes)
        waits.advance(3_000L)
        assertEquals(listOf(2, 2), port.writes)
        second.cancelAndJoin()
        val third = async(start = CoroutineStart.UNDISPATCHED) { switch.match(25f, "C") }
        waits.advance(500L)
        third.cancelAndJoin()
        val fourth = async(start = CoroutineStart.UNDISPATCHED) { switch.match(25f, "D") }
        waits.advance(2_500L)

        assertEquals(LiveDisplayModeResult.NOT_APPLIED, fourth.await().result)
        assertEquals(listOf(2, 2), port.writes)
        assertEquals(listOf(false, true), requests)
        assertEquals(listOf(1, 1, 1, 1), port.leases.map { it.closeCount })
        assertEquals(7_000L, clock.now)

        // D is the channel that timed out, even though A originally issued the request.
        assertEquals(LiveDisplayModeResult.NOT_APPLIED, switch.match(25f, "D").result)
        assertEquals(listOf(2, 2), port.writes)
        assertEquals(4, port.leases.size)
        assertTrue(waits.pending.isEmpty())
    }

    @Test
    fun `a failed target stays bounded for that channel but a later channel can retry`() = runBlocking {
        val current = mode(1, 60f)
        val target = mode(2, 50f)
        val port = Port(current, listOf(current, target))
        val clock = Clock()
        val requests = mutableListOf<Boolean>()
        val switch = switch(port, clock, requests = requests)
        switch.activate()

        assertEquals(LiveDisplayModeResult.NOT_APPLIED, switch.match(25f, "A").result)
        val elapsedAtFailure = clock.now
        repeat(10) {
            assertEquals(LiveDisplayModeResult.NOT_APPLIED, switch.match(25f, "A").result)
        }
        assertEquals(listOf(2, 2), port.writes)
        assertEquals(listOf(false, true), requests)
        assertEquals(1, port.leases.size)
        assertEquals(elapsedAtFailure, clock.now)

        port.onWrite = { port.current = target }
        assertEquals(LiveDisplayModeResult.APPLIED, switch.match(25f, "B").result)
        assertEquals(listOf(2, 2, 2), port.writes)
        assertEquals(listOf(false, true, false), requests)
        assertEquals(elapsedAtFailure + 300L, clock.now)
        assertEquals(listOf(1, 1), port.leases.map { it.closeCount })
    }

    @Test
    fun `an expired canceled request fails before acquiring another pause`() = runBlocking {
        val current = mode(1, 60f)
        val port = Port(current, listOf(current, mode(2, 50f)))
        val clock = Clock()
        val waits = PendingWait(clock)
        val switch = switch(port, clock, waits::wait)
        switch.activate()
        val first = async(start = CoroutineStart.UNDISPATCHED) { switch.match(25f, "A") }
        first.cancelAndJoin()
        clock.now = 8_000L

        assertEquals(LiveDisplayModeResult.NOT_APPLIED, switch.match(25f, "A").result)
        assertEquals(listOf(2), port.writes)
        assertEquals(listOf(1), port.leases.map { it.closeCount })
        assertTrue(waits.pending.isEmpty())
    }

    @Test
    fun `new channel already matching current cancels our stale preference without another pause`() = runBlocking {
        val current = mode(1, 60f)
        val port = Port(current, listOf(current, mode(2, 50f)))
        val clock = Clock()
        val waits = PendingWait(clock)
        val switch = switch(port, clock, waits::wait)
        switch.activate()
        val first = async(start = CoroutineStart.UNDISPATCHED) { switch.match(25f, "A") }
        first.cancelAndJoin()

        assertEquals(LiveDisplayModeResult.ALREADY_MATCHED, switch.match(30f, "B").result)
        assertEquals(listOf(2, 1), port.writes)
        assertEquals(1, port.preferredModeId)
        assertEquals(listOf(1), port.leases.map { it.closeCount })
        assertTrue(waits.pending.isEmpty())
    }

    @Test
    fun `canceling the old operation closes only its own pause lease`() = runBlocking {
        val current = mode(1, 60f)
        val fifty = mode(2, 50f)
        val twentyFour = mode(3, 24f)
        val port = Port(current, listOf(current, fifty, twentyFour))
        val clock = Clock()
        val waits = PendingWait(clock)
        val switch = switch(port, clock, waits::wait)
        switch.activate()
        val old = async(start = CoroutineStart.UNDISPATCHED) { switch.match(25f, "A") }
        val newer = async(start = CoroutineStart.UNDISPATCHED) { switch.match(24f, "B") }

        old.cancelAndJoin()
        assertEquals(listOf(1, 0), port.leases.map { it.closeCount })
        assertEquals(listOf(2, 3), port.writes)
        assertFalse(newer.isCompleted)
        port.current = twentyFour
        repeat(3) { waits.advance() }

        assertEquals(LiveDisplayModeResult.APPLIED, newer.await().result)
        assertEquals(listOf(1, 1), port.leases.map { it.closeCount })
        assertEquals(listOf(2, 3), port.writes)
    }

    @Test
    fun `release restores our own preference only when requested and is idempotent`() = runBlocking {
        for (restore in listOf(false, true)) {
            val current = mode(1, 60f)
            val target = mode(2, 50f)
            val port = Port(current, listOf(current, target), initialPreference = 42)
            port.onWrite = { if (it == target.id) port.current = target }
            val clock = Clock()
            val switch = switch(port, clock)
            switch.activate()
            assertEquals(LiveDisplayModeResult.APPLIED, switch.match(25f, "A").result)

            switch.release(restore)
            switch.release(restore)

            assertEquals(if (restore) listOf(2, 42) else listOf(2), port.writes)
            assertEquals(if (restore) 42 else 2, port.preferredModeId)
            assertEquals(LiveDisplayModeResult.INACTIVE, switch.match(25f, "A").result)
            assertEquals(1, port.leases.size)
        }
    }

    @Test
    fun `release never overwrites a later window owner`() = runBlocking {
        val current = mode(1, 60f)
        val target = mode(2, 50f)
        val port = Port(current, listOf(current, target), initialPreference = 42)
        port.onWrite = { port.current = target }
        val clock = Clock()
        val switch = switch(port, clock)
        switch.activate()
        switch.match(25f, "A")
        port.externalPreference(99)

        switch.release(restore = true)

        assertEquals(99, port.preferredModeId)
        assertEquals(listOf(2), port.writes)
    }

    @Test
    fun `release and reactivation retire old polling before it can retry or restore`() = runBlocking {
        val current = mode(1, 60f)
        val fifty = mode(2, 50f)
        val twentyFour = mode(3, 24f)
        val port = Port(current, listOf(current, fifty, twentyFour))
        val clock = Clock()
        val waits = PendingWait(clock)
        val switch = switch(port, clock, waits::wait)
        switch.activate()
        val old = async(start = CoroutineStart.UNDISPATCHED) { switch.match(25f, "A") }

        switch.release(restore = true)
        switch.activate()
        clock.now = 5_000L
        val newer = async(start = CoroutineStart.UNDISPATCHED) { switch.match(24f, "B") }
        waits.advance() // Resume the retired operation after its original retry deadline.

        assertEquals(LiveDisplayModeResult.INACTIVE, old.await().result)
        assertEquals(listOf(2, 0, 3), port.writes)
        assertEquals(listOf(1, 0), port.leases.map { it.closeCount })
        port.current = twentyFour
        repeat(3) { waits.advance() }
        assertEquals(LiveDisplayModeResult.APPLIED, newer.await().result)
        assertEquals(listOf(2, 0, 3), port.writes)
        assertEquals(listOf(1, 1), port.leases.map { it.closeCount })
    }

    @Test
    fun `START mode preference becomes the next activation baseline`() = runBlocking {
        val current = mode(1, 60f)
        val fifty = mode(2, 50f)
        val twentyFour = mode(3, 24f)
        val port = Port(current, listOf(current, fifty, twentyFour))
        port.onWrite = { id -> port.current = port.modes.first { it.id == id } }
        val clock = Clock()
        val switch = switch(port, clock)
        switch.activate()
        switch.match(25f, "A")
        switch.release(restore = false)

        switch.activate()
        switch.match(24f, "B")
        switch.release(restore = true)

        assertEquals(listOf(2, 3, 2), port.writes)
        assertEquals(2, port.preferredModeId)
    }

    @Test
    fun `OFF after leaving START fullscreen restores the original window preference`() = runBlocking {
        val current = mode(1, 60f)
        val target = mode(2, 24f)
        val port = Port(current, listOf(current, target))
        port.onWrite = { if (it == target.id) port.current = target }
        val clock = Clock()
        val switch = switch(port, clock)
        switch.activate()
        assertEquals(LiveDisplayModeResult.APPLIED, switch.match(24f, "A").result)
        switch.release(restore = false)
        assertEquals(2, port.preferredModeId)

        switch.disable()
        switch.disable()

        assertEquals(listOf(2, 0), port.writes)
        assertEquals(0, port.preferredModeId)
        assertEquals(LiveDisplayModeResult.INACTIVE, switch.match(24f, "A").result)
        assertEquals(listOf(1), port.leases.map { it.closeCount })
    }

    @Test
    fun `START STOP restores its own baseline then OFF restores the retained original baseline`() = runBlocking {
        val current = mode(1, 60f)
        val twentyFour = mode(2, 24f)
        val fifty = mode(3, 50f)
        val port = Port(current, listOf(current, twentyFour, fifty))
        port.onWrite = { id -> port.modes.firstOrNull { it.id == id }?.let { port.current = it } }
        val clock = Clock()
        val switch = switch(port, clock)
        switch.activate()
        assertEquals(LiveDisplayModeResult.APPLIED, switch.match(24f, "A").result)
        switch.release(restore = false)

        switch.activate()
        assertEquals(LiveDisplayModeResult.APPLIED, switch.match(25f, "B").result)
        switch.release(restore = true)
        assertEquals(listOf(2, 3, 2), port.writes)
        assertEquals(2, port.preferredModeId)

        switch.disable()

        assertEquals(listOf(2, 3, 2, 0), port.writes)
        assertEquals(0, port.preferredModeId)
        assertEquals(listOf(1, 1), port.leases.map { it.closeCount })
    }

    @Test
    fun `OFF while a switch is pending retires its future poll and retry`() = runBlocking {
        val current = mode(1, 60f)
        val port = Port(current, listOf(current, mode(2, 50f)))
        val clock = Clock()
        val waits = PendingWait(clock)
        val requests = mutableListOf<Boolean>()
        val switch = switch(port, clock, waits::wait, requests)
        switch.activate()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { switch.match(25f, "A") }

        switch.disable()
        waits.advance(5_000L)

        assertEquals(LiveDisplayModeResult.INACTIVE, pending.await().result)
        assertEquals(listOf(2, 0), port.writes)
        assertEquals(listOf(false), requests)
        assertEquals(listOf(1), port.leases.map { it.closeCount })
        assertTrue(waits.pending.isEmpty())
        assertEquals(LiveDisplayModeResult.INACTIVE, switch.match(25f, "A").result)
    }

    @Test
    fun `OFF respects an external window preference during and after fullscreen`() = runBlocking {
        for (leaveFullscreen in listOf(false, true)) {
            val current = mode(1, 60f)
            val target = mode(2, 24f)
            val port = Port(current, listOf(current, target))
            port.onWrite = { if (it == target.id) port.current = target }
            val clock = Clock()
            val switch = switch(port, clock)
            switch.activate()
            switch.match(24f, "A")
            if (leaveFullscreen) switch.release(restore = false)
            port.externalPreference(99)

            switch.disable()
            switch.disable()

            assertEquals(99, port.preferredModeId)
            assertEquals(listOf(2), port.writes)
            assertEquals(listOf(1), port.leases.map { it.closeCount })
        }
    }

    @Test
    fun `new requests restore the latest external baseline for START STOP and OFF`() = runBlocking {
        for (hadOwnWrite in listOf(false, true)) {
            for (restoreOnExit in listOf(false, true)) {
                val current = mode(1, 60f)
                val twentyFour = mode(2, 24f)
                val fifty = mode(3, 50f)
                val port = Port(current, listOf(current, twentyFour, fifty))
                port.onWrite = { id -> port.modes.firstOrNull { it.id == id }?.let { port.current = it } }
                val clock = Clock()
                val switch = switch(port, clock)
                switch.activate()
                if (hadOwnWrite) {
                    assertEquals(LiveDisplayModeResult.APPLIED, switch.match(24f, "A").result)
                }
                // Another window user takes over during this activation, before our next request.
                port.externalPreference(99)
                assertEquals(LiveDisplayModeResult.APPLIED, switch.match(25f, "B").result)

                switch.release(restore = restoreOnExit)
                if (restoreOnExit) assertEquals(99, port.preferredModeId)
                else assertEquals(3, port.preferredModeId)
                switch.disable()

                val trace = "ownedBeforeExternal=$hadOwnWrite, restoreOnExit=$restoreOnExit"
                assertEquals(trace, 99, port.preferredModeId)
                assertEquals(trace, if (hadOwnWrite) listOf(2, 3, 99) else listOf(3, 99), port.writes)
                assertEquals(trace, if (hadOwnWrite) listOf(1, 1) else listOf(1), port.leases.map { it.closeCount })
            }
        }
    }

    @Test
    fun `reenabling after OFF gets a fresh baseline and request budget`() = runBlocking {
        for (firstRequestFails in listOf(false, true)) {
            val current = mode(1, 60f)
            val target = mode(2, 24f)
            val port = Port(current, listOf(current, target))
            if (!firstRequestFails) port.onWrite = { if (it == target.id) port.current = target }
            val clock = Clock()
            val requests = mutableListOf<Boolean>()
            val switch = switch(port, clock, requests = requests)
            switch.activate()
            assertEquals(
                if (firstRequestFails) LiveDisplayModeResult.NOT_APPLIED else LiveDisplayModeResult.APPLIED,
                switch.match(24f, "A").result
            )
            switch.release(restore = false)
            switch.disable()
            assertEquals(0, port.preferredModeId)
            port.externalPreference(77)
            port.current = current
            port.onWrite = { if (it == target.id) port.current = target }

            switch.activate()
            assertEquals(LiveDisplayModeResult.APPLIED, switch.match(24f, "A").result)
            switch.release(restore = false)
            switch.disable()

            assertEquals(if (firstRequestFails) listOf(2, 2, 0, 2, 77) else listOf(2, 0, 2, 77), port.writes)
            assertEquals(if (firstRequestFails) listOf(false, true, false) else listOf(false, false), requests)
            assertEquals(77, port.preferredModeId)
            assertEquals(listOf(1, 1), port.leases.map { it.closeCount })
        }
    }

    @Test
    fun `inactive and unavailable modes have no window or playback side effects`() = runBlocking {
        val port = Port(mode(1, 60f), emptyList())
        val clock = Clock()
        val switch = switch(port, clock)
        assertEquals(LiveDisplayModeResult.INACTIVE, switch.match(30f, "A").result)
        switch.activate()
        assertEquals(LiveDisplayModeResult.UNAVAILABLE, switch.match(30f, "A").result)
        port.current = null
        assertEquals(LiveDisplayModeResult.UNAVAILABLE, switch.match(30f, "A").result)
        assertTrue(port.writes.isEmpty())
        assertTrue(port.leases.isEmpty())
        assertTrue(clock.waits.isEmpty())
    }

    private class Port(
        var current: LiveDisplayMode?,
        val modes: List<LiveDisplayMode>,
        initialPreference: Int = 0
    ) : LiveDisplayModePort {
        val writes = mutableListOf<Int>()
        val leases = mutableListOf<Lease>()
        var onWrite: (Int) -> Unit = {}
        private var preference = initialPreference
        override var preferredModeId: Int
            get() = preference
            set(value) {
                writes += value
                preference = value
                onWrite(value)
            }

        override fun currentMode() = current
        override fun supportedModes() = modes
        override fun pausePlayback(): AutoCloseable = Lease().also { leases += it }
        fun externalPreference(value: Int) { preference = value }
    }

    private class Lease : AutoCloseable {
        var closeCount = 0
        override fun close() { closeCount++ }
    }

    private class Clock {
        var now = 0L
        val waits = mutableListOf<Long>()
        fun advance(millis: Long) {
            waits += millis
            now += millis
        }
    }

    /** Suspends until the test advances one specific pending poll, without wall-clock sleeps. */
    private class PendingWait(private val clock: Clock) {
        class Tick(val requestedMs: Long, val ready: CompletableDeferred<Unit> = CompletableDeferred())
        val pending = mutableListOf<Tick>()

        suspend fun wait(millis: Long) {
            val tick = Tick(millis)
            pending += tick
            try {
                tick.ready.await()
            } finally {
                pending.remove(tick)
            }
        }

        suspend fun advance(elapsedMs: Long? = null) {
            check(pending.isNotEmpty()) { "No display poll is waiting" }
            val tick = pending.first()
            clock.advance(elapsedMs ?: tick.requestedMs)
            tick.ready.complete(Unit)
            yield()
        }
    }

    private fun switch(
        port: Port,
        clock: Clock,
        wait: suspend (Long) -> Unit = { clock.advance(it) },
        requests: MutableList<Boolean> = mutableListOf()
    ) = LiveDisplayModeSwitch(port, { clock.now }, wait) { _, _, _, retry -> requests += retry }

    private fun mode(id: Int, refresh: Float) = LiveDisplayMode(id, 1920, 1080, refresh)
}
