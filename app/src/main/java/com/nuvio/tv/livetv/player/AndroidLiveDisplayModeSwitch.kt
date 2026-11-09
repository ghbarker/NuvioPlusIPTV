package com.nuvio.tv.livetv.player

import android.app.Activity
import android.os.SystemClock
import android.view.Display
import com.nuvio.tv.livetv.model.LiveTvLoadReport

/** Activity lifetime ownership keeps On-start overrides releasable from Settings. */
internal fun liveDisplayModeSwitch(activity: Activity, playback: LiveTvPlaybackController): LiveDisplayModeSwitch {
    val port = object : LiveDisplayModePort {
        @Suppress("DEPRECATION")
        private fun display() = activity.window.decorView.display ?: activity.windowManager.defaultDisplay

        override var preferredModeId: Int
            get() = activity.window.attributes.preferredDisplayModeId
            set(value) {
                val attrs = activity.window.attributes
                attrs.preferredDisplayModeId = value
                activity.window.attributes = attrs
            }

        override fun currentMode() = display()?.mode?.liveMode()
        override fun supportedModes() = display()?.supportedModes?.map { it.liveMode() }.orEmpty()
        override fun pausePlayback() = playback.pauseForDisplaySwitch()
    }
    return LiveDisplayModeSwitch(port, SystemClock::elapsedRealtime) { from, to, fps, retry ->
        LiveTvLoadReport.add(
            if (retry) "Match frame rate: retrying %.3f Hz once".format(to.refreshHz)
            else "Match frame rate: video %.3f fps, display %.3f Hz → %.3f Hz".format(fps, from.refreshHz, to.refreshHz)
        )
    }
}

private fun Display.Mode.liveMode() = LiveDisplayMode(modeId, physicalWidth, physicalHeight, refreshRate)
