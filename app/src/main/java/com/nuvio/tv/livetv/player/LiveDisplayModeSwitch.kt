package com.nuvio.tv.livetv.player

import kotlinx.coroutines.delay

/** The window adapter belongs to the Activity; all calls stay on its UI thread. */
internal interface LiveDisplayModePort {
    var preferredModeId: Int
    fun currentMode(): LiveDisplayMode?
    fun supportedModes(): List<LiveDisplayMode>
    fun pausePlayback(): AutoCloseable
}

internal enum class LiveDisplayModeResult { ALREADY_MATCHED, APPLIED, UNAVAILABLE, NOT_APPLIED, INACTIVE }

internal data class LiveDisplayModeOutcome(
    val result: LiveDisplayModeResult,
    val current: LiveDisplayMode? = null,
    val target: LiveDisplayMode? = null,
    val available: List<LiveDisplayMode> = emptyList()
)

/** One owner of a window preference, including across FPS polls and channel changes.
 *
 * A requested mode can take seconds to become the reported display mode. Re-entering
 * the effect during that interval must wait for that request, rather than issuing it
 * again. The single retry budget belongs to the request, not the coroutine polling it.
 */
internal class LiveDisplayModeSwitch(
    private val port: LiveDisplayModePort,
    private val nowMs: () -> Long,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val onRequest: (LiveDisplayMode, LiveDisplayMode, Float, Boolean) -> Unit = { _, _, _, _ -> }
) {
    private data class Request(
        val target: LiveDisplayMode,
        var sessionKey: Any?,
        val startedMs: Long,
        var attempts: Int = 1,
        var complete: Boolean = false,
        var failed: Boolean = false
    )

    private var active = false
    private var activation = 0L
    private var operation = 0L
    private var originalPreference = 0
    private var retainedOriginalPreference: Int? = null
    private var ownedPreference: Int? = null
    private var request: Request? = null

    fun activate() {
        if (active) return
        originalPreference = port.preferredModeId
        checkPreferenceOwner()
        request = null
        activation++
        active = true
    }

    /** Restore only the preference this owner actually wrote, never a newer owner's setting. */
    fun release(restore: Boolean) {
        if (!active) return
        active = false
        activation++
        operation++
        val owned = ownedPreference
        if (restore && owned != null && port.preferredModeId == owned && originalPreference != owned) {
            writePreference(originalPreference)
        }
        request = null
    }

    /** OFF also releases an On-start override retained after leaving fullscreen. */
    fun disable() {
        active = false
        activation++
        operation++
        val original = retainedOriginalPreference
        val owned = ownedPreference
        try {
            if (original != null && owned != null && port.preferredModeId == owned && original != owned) {
                port.preferredModeId = original
            }
        } finally {
            ownedPreference = null
            retainedOriginalPreference = null
            request = null
        }
    }

    private fun checkPreferenceOwner() {
        val preference = port.preferredModeId
        if (ownedPreference != preference) {
            // If another window user took over, a later request starts a new
            // ownership chain. START_STOP must restore that newer baseline too.
            originalPreference = preference
            ownedPreference = null
            retainedOriginalPreference = null
        }
    }

    private fun writePreference(value: Int) {
        checkPreferenceOwner()
        val original = retainedOriginalPreference ?: port.preferredModeId
        port.preferredModeId = value
        retainedOriginalPreference = original
        ownedPreference = value
    }

    suspend fun match(fps: Float, sessionKey: Any?): LiveDisplayModeOutcome {
        if (!active) return LiveDisplayModeOutcome(LiveDisplayModeResult.INACTIVE)
        val epoch = activation
        val token = ++operation
        fun ownsOperation() = active && activation == epoch && operation == token
        val current = port.currentMode()
            ?: return LiveDisplayModeOutcome(LiveDisplayModeResult.UNAVAILABLE)
        val available = port.supportedModes()
        val target = chooseLiveDisplayMode(current, available, fps)
            ?: return LiveDisplayModeOutcome(LiveDisplayModeResult.UNAVAILABLE, current, available = available)

        if (target === current) {
            val old = request
            // A newer channel may already fit the *current* rate while an older
            // request is still pending. Replace our stale request before returning.
            if (old != null && ownedPreference == port.preferredModeId &&
                !liveDisplayModesEquivalent(old.target, current)
            ) {
                writePreference(current.id)
                request = Request(current, sessionKey, nowMs(), complete = true)
            } else {
                old?.complete = true
            }
            return LiveDisplayModeOutcome(LiveDisplayModeResult.ALREADY_MATCHED, current, current)
        }

        val old = request
        val samePending = old != null && liveDisplayModesEquivalent(old.target, target) &&
            ownedPreference == port.preferredModeId && !old.complete &&
            (!old.failed || old.sessionKey == sessionKey)
        val pending = if (samePending) old else Request(target, sessionKey, nowMs())
        pending.sessionKey = sessionKey
        if (samePending && nowMs() - pending.startedMs >= TIMEOUT_MS) pending.failed = true
        if (pending.failed) {
            return LiveDisplayModeOutcome(LiveDisplayModeResult.NOT_APPLIED, current, target)
        }
        request = pending
        val pause = port.pausePlayback()
        try {
            if (!ownsOperation()) return LiveDisplayModeOutcome(LiveDisplayModeResult.INACTIVE)
            if (!samePending) {
                onRequest(current, target, fps, false)
                writePreference(target.id)
            }
            var stable = 0
            while (ownsOperation()) {
                wait(POLL_MS)
                if (!ownsOperation()) break
                val observed = port.currentMode()
                val reached = observed != null &&
                    (observed.id == target.id && observed.width == target.width && observed.height == target.height ||
                        chooseLiveDisplayMode(observed, listOf(target), fps) === observed)
                stable = if (reached) stable + 1 else 0
                if (stable >= STABLE_POLLS) {
                    pending.complete = true
                    return LiveDisplayModeOutcome(LiveDisplayModeResult.APPLIED, observed, target)
                }
                val elapsed = nowMs() - pending.startedMs
                if (!reached && elapsed >= RETRY_MS && pending.attempts < MAX_ATTEMPTS) {
                    pending.attempts++
                    onRequest(observed ?: current, target, fps, true)
                    writePreference(target.id)
                }
                if (elapsed >= TIMEOUT_MS) {
                    pending.failed = true
                    return LiveDisplayModeOutcome(LiveDisplayModeResult.NOT_APPLIED, observed, target)
                }
            }
            return LiveDisplayModeOutcome(LiveDisplayModeResult.INACTIVE)
        } finally {
            pause.close()
        }
    }

    private companion object {
        const val POLL_MS = 100L
        const val STABLE_POLLS = 3
        const val RETRY_MS = 4_000L
        const val TIMEOUT_MS = 7_000L
        const val MAX_ATTEMPTS = 2
    }
}
