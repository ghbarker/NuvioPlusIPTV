package com.nuvio.tv.livetv.player

import kotlin.math.abs

/** Values needed to choose a mode; does not depend on Android display objects. */
internal data class LiveDisplayMode(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshHz: Float
)

/** Different mode IDs can describe the same timing on TV display drivers. */
internal fun liveDisplayModesEquivalent(first: LiveDisplayMode, second: LiveDisplayMode): Boolean =
    first.isValid() && second.isValid() && first.width == second.width && first.height == second.height &&
        abs(first.refreshHz - second.refreshHz) < EQUIVALENT_REFRESH_HZ

/** Keeps the existing cadence and resolution preferences. Returns the exact
 * [current] instance when that fitting timing can remain active without a request. */
internal fun chooseLiveDisplayMode(
    current: LiveDisplayMode,
    candidates: List<LiveDisplayMode>,
    fps: Float
): LiveDisplayMode? {
    if (!fps.isFinite() || fps <= 0f || current.width <= 0 || current.height <= 0) return null
    val best = candidates.asSequence()
        .filter { it.isValid() && it.width == current.width && it.height == current.height }
        .filter { refreshError(it.refreshHz, fps) <= 0.6f }
        .minWithOrNull(
            compareBy<LiveDisplayMode>(
                { if (refreshError(it.refreshHz, fps) < 0.02f) 0 else 1 },
                { if (it.refreshHz <= 61f) 0 else 1 },
                { -it.refreshHz }
            )
        ) ?: return null
    // Compare after ranking: an already active 25 Hz mode must not beat the
    // preferred 50 Hz mode for sports merely because both fit 25 fps.
    return if (refreshError(current.refreshHz, fps) <= 0.6f && liveDisplayModesEquivalent(current, best)) current else best
}

private fun LiveDisplayMode.isValid(): Boolean =
    id > 0 && width > 0 && height > 0 && refreshHz.isFinite() && refreshHz > 0f

private fun refreshError(refreshHz: Float, fps: Float): Float =
    (1..4).minOf { multiple -> abs(refreshHz - fps * multiple) }

// 0.05 Hz also merges 23.976/24 and 29.97/30. Those cadences must stay distinct.
private const val EQUIVALENT_REFRESH_HZ = 0.01f
