package com.nuvio.tv.livetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LiveDisplayModeMatchingTest {
    @Test
    fun `current equivalent timing wins even when its mode id is absent from the supported list`() {
        val current = mode(7, 59.94f)
        val modes = listOf(mode(3, 59.94f), mode(4, 60f))
        assertSame(current, chooseLiveDisplayMode(current, modes, 59.94f))
        assertTrue(liveDisplayModesEquivalent(current, modes.first()))
    }

    @Test
    fun `equivalent timing under different ids stays selected across repeated channel selections`() {
        val current = mode(7, 60f)
        repeat(100) { channel ->
            val duplicate = mode(100 + channel, 60f)
            assertSame(current, chooseLiveDisplayMode(current, listOf(duplicate), 30f))
            assertTrue(liveDisplayModesEquivalent(current, duplicate))
        }
    }

    @Test
    fun `integer and fractional rates remain distinct in either direction`() {
        for ((fractional, integer) in listOf(23.976f to 24f, 29.97f to 30f, 59.94f to 60f)) {
            val fraction = mode(1, fractional)
            val whole = mode(2, integer)
            assertFalse(liveDisplayModesEquivalent(fraction, whole))
            assertFalse(liveDisplayModesEquivalent(whole, fraction))
            assertEquals(whole, chooseLiveDisplayMode(fraction, listOf(fraction, whole), integer))
            assertEquals(fraction, chooseLiveDisplayMode(whole, listOf(fraction, whole), fractional))
        }
    }

    @Test
    fun `equivalence tolerance is strictly below one hundredth of a hertz`() {
        assertTrue(liveDisplayModesEquivalent(mode(1, 60f), mode(2, 60.005f)))
        assertFalse(liveDisplayModesEquivalent(mode(1, 60f), mode(2, 60.011f)))
        // These two positive Float values have an exactly representable difference of 0.01f.
        assertFalse(liveDisplayModesEquivalent(mode(1, 0.01f), mode(2, 0.02f)))
    }

    @Test
    fun `an equivalent current timing outside the fitting limit cannot replace a fitting supported mode`() {
        val current = mode(1, 60.605f)
        val fitting = mode(2, 60.599f)
        assertTrue(liveDisplayModesEquivalent(current, fitting))
        assertEquals(fitting, chooseLiveDisplayMode(current, listOf(fitting), 60f))
    }

    @Test
    fun `PAL sports still chooses 50 hertz over the active 25 hertz mode`() {
        val current = mode(1, 25f)
        val preferred = mode(2, 50f)
        assertEquals(preferred, chooseLiveDisplayMode(current, listOf(current, preferred, mode(3, 100f)), 25f))
    }

    @Test
    fun `higher refresh fallback keeps the existing preference order`() {
        val current = mode(1, 50f)
        val sixty = mode(2, 60f)
        val oneTwenty = mode(3, 120f)
        assertEquals(sixty, chooseLiveDisplayMode(current, listOf(oneTwenty, sixty), 30f))
        assertEquals(oneTwenty, chooseLiveDisplayMode(current, listOf(oneTwenty), 30f))
        assertEquals(oneTwenty, chooseLiveDisplayMode(current, listOf(oneTwenty, sixty), 120f))
        // The exact fractional cadence wins before the under-61-Hz preference.
        val exactFraction = mode(4, 119.88f)
        assertEquals(exactFraction, chooseLiveDisplayMode(current, listOf(sixty, exactFraction), 29.97f))
    }

    @Test
    fun `resolution never changes as part of choosing a cadence`() {
        val current = mode(1, 60f)
        val otherResolution = LiveDisplayMode(2, 3840, 2160, 50f)
        assertNull(chooseLiveDisplayMode(current, listOf(otherResolution), 25f))
        assertFalse(liveDisplayModesEquivalent(current, otherResolution.copy(refreshHz = 60f)))
        assertFalse(liveDisplayModesEquivalent(current, current.copy(id = 2, width = 1280)))
        assertFalse(liveDisplayModesEquivalent(current, current.copy(id = 2, height = 720)))
    }

    @Test
    fun `invalid frame rates and candidate descriptions cannot be selected`() {
        val current = mode(1, 60f)
        for (fps in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            assertNull(chooseLiveDisplayMode(current, listOf(mode(2, 60f)), fps))
        }
        for (invalid in listOf(
            mode(2, Float.NaN), mode(2, Float.POSITIVE_INFINITY), mode(2, -60f), mode(2, 0f),
            current.copy(id = 0), current.copy(width = 0), current.copy(height = -1)
        )) {
            assertFalse(liveDisplayModesEquivalent(current, invalid))
            assertFalse(liveDisplayModesEquivalent(invalid, current))
            assertNull(chooseLiveDisplayMode(current, listOf(invalid), 60f))
        }
        assertNull(chooseLiveDisplayMode(current.copy(width = 0), listOf(current), 60f))
        assertNull(chooseLiveDisplayMode(current.copy(height = 0), listOf(current), 60f))
        assertNull(chooseLiveDisplayMode(current, emptyList(), 60f))
    }

    @Test
    fun `unknown current refresh can still choose a valid supported timing without assuming equivalence`() {
        val supported = mode(2, 60f)
        for (unknown in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) {
            val current = mode(1, unknown)
            assertFalse(liveDisplayModesEquivalent(current, supported))
            assertEquals(supported, chooseLiveDisplayMode(current, listOf(supported), 60f))
        }
    }

    @Test
    fun `ordinary mode selection matches the existing selector across cadence and display combinations`() {
        val refreshRates = listOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f)
        val fpsValues = refreshRates + listOf(12.5f, 20f, 26.7f, 29.96f, 30.01f, 59.97f, 60.005f)
        val allModes = refreshRates.mapIndexed { index, refresh -> mode(index + 1, refresh) }
        val sets = buildList {
            for (a in allModes.indices) {
                add(listOf(allModes[a]))
                for (b in a + 1 until allModes.size) {
                    add(listOf(allModes[a], allModes[b]))
                    for (c in b + 1 until allModes.size) add(listOf(allModes[a], allModes[b], allModes[c]))
                }
            }
        }
        var comparisons = 0
        for (modes in sets) {
            val current = modes.last()
            val candidates = modes + LiveDisplayMode(100, 3840, 2160, 50f)
            for (fps in fpsValues) {
                assertEquals("$fps fps, $modes", legacyChoice(current, candidates, fps), chooseLiveDisplayMode(current, candidates, fps))
                comparisons++
            }
        }
        assertTrue(comparisons > 7_000)
    }

    /** Oracle retained from the previous Android selector; no equivalence shortcut. */
    private fun legacyChoice(current: LiveDisplayMode, candidates: List<LiveDisplayMode>, fps: Float): LiveDisplayMode? {
        fun error(refresh: Float): Float = (1..4).minOf { k -> abs(refresh - fps * k) }
        return candidates.filter { it.width == current.width && it.height == current.height }
            .filter { error(it.refreshHz) <= 0.6f }
            .sortedWith(compareBy<LiveDisplayMode>(
                { if (error(it.refreshHz) < 0.02f) 0 else 1 },
                { if (it.refreshHz <= 61f) 0 else 1 },
                { -it.refreshHz }
            ))
            .firstOrNull()
    }

    private fun mode(id: Int, refresh: Float) = LiveDisplayMode(id, 1920, 1080, refresh)
}
