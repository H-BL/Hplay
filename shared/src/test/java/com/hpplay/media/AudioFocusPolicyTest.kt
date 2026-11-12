package com.hpplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the navigation-ducking policy: a prompt that asks to duck must lower the music, not silence
 * the whole session, while a call or a camera still pauses everything.
 */
class AudioFocusPolicyTest {
    @Test
    fun navigationIsAPromptAndMediaIsNot() {
        assertTrue(AudioFocusPolicy.isPrompt(AudioChannel.NAVIGATION))
        assertFalse(AudioFocusPolicy.isPrompt(AudioChannel.MEDIA))
        assertFalse(AudioFocusPolicy.isPrompt(AudioChannel.PHONE))
        assertFalse(AudioFocusPolicy.isPrompt(AudioChannel.ASSISTANT))
    }

    @Test
    fun aDuckRequestDucksMusicAndPausesOtherPrompts() {
        val duck = AudioFocusPolicy.FOCUS_LOSS_TRANSIENT_CAN_DUCK
        assertEquals(FocusReaction.DUCK, AudioFocusPolicy.reactionFor(AudioChannel.MEDIA, duck))
        assertEquals(FocusReaction.PAUSE, AudioFocusPolicy.reactionFor(AudioChannel.NAVIGATION, duck))
        assertEquals(FocusReaction.PAUSE, AudioFocusPolicy.reactionFor(AudioChannel.PHONE, duck))
        assertEquals(FocusReaction.PAUSE, AudioFocusPolicy.reactionFor(AudioChannel.ASSISTANT, duck))
        assertTrue(AudioFocusPolicy.ducksUnderPrompt(AudioChannel.MEDIA))
        assertFalse(AudioFocusPolicy.ducksUnderPrompt(AudioChannel.PHONE))
    }

    @Test
    fun aHardOrTransientLossPausesEverything() {
        for (change in listOf(AudioFocusPolicy.FOCUS_LOSS, AudioFocusPolicy.FOCUS_LOSS_TRANSIENT)) {
            for (channel in AudioChannel.entries) {
                assertEquals(
                    "channel=$channel change=$change",
                    FocusReaction.PAUSE,
                    AudioFocusPolicy.reactionFor(channel, change),
                )
            }
        }
    }

    @Test
    fun regainedFocusPlaysEverything() {
        for (channel in AudioChannel.entries) {
            assertEquals(FocusReaction.PLAY, AudioFocusPolicy.reactionFor(channel, AudioFocusPolicy.FOCUS_GAIN))
        }
    }

    @Test
    fun theDuckVolumeIsAudibleButLowerThanFull() {
        assertTrue(AudioFocusPolicy.DUCK_VOLUME > 0f)
        assertTrue(AudioFocusPolicy.DUCK_VOLUME < 1f)
    }
}
