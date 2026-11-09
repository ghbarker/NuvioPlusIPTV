package com.nuvio.tv.livetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDisplaySwitchPauseTest {
    private class Playback(var ready: Boolean = true, var session: Int = 1) {
        val events = mutableListOf<Boolean>()
        fun ready(value: Boolean) { ready = value; events += value }
    }

    private fun LiveDisplaySwitchPause.pause(player: Playback): AutoCloseable {
        val session = player.session
        return acquire(player, player.ready, { player.session == session },
            { player.ready(false) }, { if (!player.ready) player.ready(true) })
    }

    @Test fun `one switch resumes its original playback exactly once`() {
        val gate = LiveDisplaySwitchPause()
        val player = Playback()
        val token = gate.pause(player)
        assertFalse(player.ready)
        assertTrue(gate.intendedPlaying(player))
        token.close()
        token.close()
        assertTrue(player.ready)
        assertFalse(gate.intendedPlaying(player))
        assertEquals(listOf(false, true), player.events)
    }

    @Test fun `switching a paused stream never starts playback`() {
        val gate = LiveDisplaySwitchPause()
        val player = Playback(ready = false)
        gate.pause(player).close()
        assertFalse(player.ready)
        assertFalse(gate.intendedPlaying(player))
        assertTrue(player.events.isEmpty())
    }

    @Test fun `initially invalid playback cannot be paused even when play is requested`() {
        val gate = LiveDisplaySwitchPause()
        val player = Playback()
        gate.acquire(player, true, { false },
            { player.ready(false) }, { player.ready(true) }).close()
        assertTrue(player.ready)
        assertFalse(gate.intendedPlaying(player))
        assertTrue(player.events.isEmpty())
    }

    @Test fun `invalid new request leaves an existing valid pause owned`() {
        val gate = LiveDisplaySwitchPause()
        val playing = Playback()
        val invalid = Playback()
        val validToken = gate.pause(playing)
        val invalidToken = gate.acquire(invalid, true, { false },
            { invalid.ready(false) }, { invalid.ready(true) })
        invalidToken.close()
        assertFalse(playing.ready)
        assertTrue(gate.intendedPlaying(playing))
        assertTrue(invalid.ready)
        assertTrue(invalid.events.isEmpty())
        validToken.close()
        assertTrue(playing.ready)
        assertEquals(listOf(false, true), playing.events)
    }

    @Test fun `new switch inherits temporary pause and old close cannot resume it`() {
        val gate = LiveDisplaySwitchPause()
        val player = Playback()
        val first = gate.pause(player)
        val second = gate.pause(player)
        first.close()
        assertFalse(player.ready)
        assertTrue(gate.intendedPlaying(player))
        second.close()
        assertTrue(player.ready)
        assertEquals(listOf(false, true), player.events)
    }

    @Test fun `newer switch can finish before the canceled older switch`() {
        val gate = LiveDisplaySwitchPause()
        val player = Playback()
        val first = gate.pause(player)
        val second = gate.pause(player)
        second.close()
        player.ready(false) // A later user pause must survive the stale close.
        first.close()
        assertFalse(player.ready)
        assertEquals(listOf(false, true, false), player.events)
    }

    @Test fun `user pause invalidates old intent even when already temporarily paused`() {
        val gate = LiveDisplaySwitchPause()
        val player = Playback()
        val first = gate.pause(player)
        gate.invalidate()
        assertFalse(gate.intendedPlaying(player))
        val second = gate.pause(player)
        first.close()
        second.close()
        assertFalse(player.ready)
        assertEquals(listOf(false), player.events)
    }

    @Test fun `replaced player cannot inherit or resume the old player`() {
        val gate = LiveDisplaySwitchPause()
        val old = Playback()
        val replacement = Playback(ready = false)
        val first = gate.pause(old)
        val second = gate.pause(replacement)
        first.close()
        second.close()
        assertFalse(old.ready)
        assertFalse(replacement.ready)
        assertEquals(listOf(false), old.events)
        assertTrue(replacement.events.isEmpty())
    }

    @Test fun `changed stream on the same player cannot inherit or resume old intent`() {
        val gate = LiveDisplaySwitchPause()
        val player = Playback()
        val first = gate.pause(player)
        player.session++
        assertFalse(gate.intendedPlaying(player))
        val second = gate.pause(player)
        first.close()
        second.close()
        assertFalse(player.ready)
        assertEquals(listOf(false), player.events)
    }

    @Test fun `background stop or release retires the pending resume`() {
        for (transition in listOf("background", "stop", "release")) {
            val gate = LiveDisplaySwitchPause()
            val player = Playback()
            val token = gate.pause(player)
            gate.invalidate()
            token.close()
            assertFalse(transition, player.ready)
        }
    }

    @Test fun `an independently resumed stream receives no duplicate play command`() {
        val gate = LiveDisplaySwitchPause()
        val player = Playback()
        val token = gate.pause(player)
        player.ready(true)
        token.close()
        assertEquals(listOf(false, true), player.events)
    }

    @Test fun `playing intent is visible before the internal pause callback`() {
        val gate = LiveDisplaySwitchPause()
        val player = Playback()
        gate.acquire(player, true, { true }, {
            assertTrue(gate.intendedPlaying(player))
            player.ready(false)
        }, { player.ready(true) }).close()
        assertTrue(player.ready)
    }

    @Test fun `failed pause restores only playback whose pause is still owned`() {
        for (userCanceled in listOf(false, true)) {
            val gate = LiveDisplaySwitchPause()
            val player = Playback()
            val failure = IllegalStateException("Synthetic pause failure")
            try {
                gate.acquire(player, true, { true }, {
                    player.ready(false)
                    if (userCanceled) gate.invalidate()
                    throw failure
                }, { player.ready(true) })
                throw AssertionError("Pause failure must propagate")
            } catch (actual: IllegalStateException) {
                assertTrue(actual === failure)
            }
            assertFalse(gate.intendedPlaying(player))
            assertEquals(!userCanceled, player.ready)
        }
    }
}
