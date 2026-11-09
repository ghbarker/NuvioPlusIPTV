package com.nuvio.tv.livetv.player

/** Owns only the temporary pause needed by a display switch. A newer switch can
 * take over the same playback's pause without losing its original play intent. */
internal class LiveDisplaySwitchPause {
    private class Pause(
        val owner: Any,
        val shouldResume: Boolean,
        val stillCurrent: () -> Boolean,
        val resume: () -> Unit
    )

    private var active: Pause? = null

    fun intendedPlaying(owner: Any?): Boolean = active?.let {
        it.owner === owner && it.shouldResume && it.stillCurrent()
    } == true

    /** A user command, backgrounding or stream teardown takes ownership away. */
    fun invalidate() {
        active = null
    }

    fun acquire(
        owner: Any,
        playWhenReady: Boolean,
        stillCurrent: () -> Boolean,
        pause: () -> Unit,
        resume: () -> Unit
    ): AutoCloseable {
        // A stale FPS/surface update may refer to playback that has not started,
        // ended, or been replaced. It must neither pause it nor take an existing
        // valid switch's ownership away.
        if (!stillCurrent()) return AutoCloseable { }
        val previous = active
        val inherited = previous != null && previous.owner === owner &&
            previous.shouldResume && previous.stillCurrent()
        val token = Pause(owner, playWhenReady || inherited, stillCurrent, resume)
        active = token
        if (playWhenReady) {
            try {
                pause()
            } catch (failure: Throwable) {
                if (active === token) {
                    active = null
                    try {
                        if (token.shouldResume && token.stillCurrent()) token.resume()
                    } catch (restoreFailure: Throwable) {
                        failure.addSuppressed(restoreFailure)
                    }
                }
                throw failure
            }
        }
        return AutoCloseable {
            if (active === token) {
                active = null
                if (token.shouldResume && token.stillCurrent()) token.resume()
            }
        }
    }
}
