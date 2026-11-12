package com.hpplay.media

/** How a stream should react to another stream or app taking audio focus. */
internal enum class FocusReaction {
    /** Keep playing at full volume. */
    PLAY,

    /** Lower the volume for the duration of the interruption, then restore it. */
    DUCK,

    /** Stop until focus comes back. */
    PAUSE,
}

/**
 * Decides how each CarPlay stream reacts to an audio-focus change.
 *
 * Navigation prompts must be heard without silencing the music underneath: when the phone (or
 * another app) starts a navigation prompt it takes focus with a duck request, and this app should
 * duck its media instead of pausing it. Only the streams that would actually be silenced by the
 * prompt are affected, so a phone call still pauses everything, and a media stream keeps its place
 * under a prompt.
 *
 * Kept pure and separate from [AudioTrack] so the policy can be unit-tested without a device.
 */
internal object AudioFocusPolicy {
    /** True when [channel] is a spoken prompt that should sit on top of music, not replace it. */
    fun isPrompt(channel: AudioChannel): Boolean = channel == AudioChannel.NAVIGATION

    /**
     * True when [channel] is the music stream, the only one that lowers its volume under a prompt.
     *
     * A call or an assistant prompt must not be attenuated by a navigation prompt (that would make
     * the two talk over each other), so only plain media ducks.
     */
    fun ducksUnderPrompt(channel: AudioChannel): Boolean = channel == AudioChannel.MEDIA

    /**
     * How a stream on [channel] should react to [change].
     *
     * A duck request from a prompt (navigation, an assistant) is answered by ducking the music and
     * the other spoken channels; a hard or transient loss still pauses everything.
     */
    fun reactionFor(channel: AudioChannel, change: Int): FocusReaction = when (change) {
        FOCUS_GAIN -> FocusReaction.PLAY
        FOCUS_LOSS -> FocusReaction.PAUSE
        FOCUS_LOSS_TRANSIENT -> FocusReaction.PAUSE
        FOCUS_LOSS_TRANSIENT_CAN_DUCK ->
            if (ducksUnderPrompt(channel)) FocusReaction.DUCK else FocusReaction.PAUSE
        else -> FocusReaction.PLAY
    }

    /** Linear volume for a ducked stream (kept above silence so speech is still intelligible). */
    const val DUCK_VOLUME = 0.2f

    // Local copies so this stays a pure Kotlin file that the unit tests can load without Android.
    const val FOCUS_GAIN = 1
    const val FOCUS_LOSS = -1
    const val FOCUS_LOSS_TRANSIENT = -2
    const val FOCUS_LOSS_TRANSIENT_CAN_DUCK = -3
}
