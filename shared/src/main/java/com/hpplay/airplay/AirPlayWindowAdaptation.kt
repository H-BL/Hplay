package com.hpplay.airplay

import kotlin.math.abs

/** A panel or window size in pixels. */
data class AirPlayWindowSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Window dimensions must be positive" }
    }
}

/**
 * How the CarPlay handshake size should follow the current window.
 *
 * @param displaySize pixels to advertise in `/info` for the next handshake.
 * @param reconnect whether the running session must be rebuilt to adopt [displaySize].
 * @param multiWindow whether [displaySize] describes a window smaller than the panel.
 */
data class AirPlayWindowDecision(
    val displaySize: AirPlayWindowSize,
    val reconnect: Boolean,
    val multiWindow: Boolean,
)

/**
 * Matches the advertised CarPlay display to the window the video is rendered into.
 *
 * The receiver renders exactly one display and that display is the app window: a full-screen window
 * negotiates the panel size, while a shrunken window (desktop picture-in-picture, split screen,
 * freeform) negotiates the window size. Keeping that mapping explicit lets the host treat "the
 * window shrank" differently from "the panel changed".
 */
object AirPlayWindowAdaptation {
    /** Window sizes within this fraction of the panel count as full screen. */
    const val FULL_SCREEN_TOLERANCE = 0.02

    fun isFullScreen(panel: AirPlayWindowSize, window: AirPlayWindowSize): Boolean =
        withinTolerance(panel.width, window.width) && withinTolerance(panel.height, window.height)

    /**
     * @param adaptive whether multi-window adaptation is enabled at all.
     * @param reconnectOnResize whether a resize may restart the session; when false the stream is
     *   kept and scaled to the window instead.
     */
    fun decide(
        panel: AirPlayWindowSize,
        window: AirPlayWindowSize,
        adaptive: Boolean,
        reconnectOnResize: Boolean,
    ): AirPlayWindowDecision {
        if (!adaptive) {
            return AirPlayWindowDecision(window, reconnect = false, multiWindow = false)
        }
        if (isFullScreen(panel, window)) {
            return AirPlayWindowDecision(window, reconnect = false, multiWindow = false)
        }
        return AirPlayWindowDecision(window, reconnect = reconnectOnResize, multiWindow = true)
    }

    private fun withinTolerance(reference: Int, value: Int): Boolean =
        abs(value - reference).toDouble() / reference <= FULL_SCREEN_TOLERANCE
}
