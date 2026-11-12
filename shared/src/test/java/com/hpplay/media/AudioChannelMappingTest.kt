package com.hpplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioChannelMappingTest {
    @Test
    fun mobileCompatibleKeepsOnlyCallsAndAlertsOffTheMediaChannel() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "alert",
            payloadType = 100,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
        )
        // "default" is the CarPlay navigation-prompt type, so it is NOT media anymore.
        listOf("media", "speechRecognition", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.MEDIA,
                contentType = AudioContentType.MUSIC,
            )
        }
    }

    @Test
    fun cameraTriggeredVoiceSessionsDoNotMoveTheCarSpeakersToTheVoiceChannel() {
        // A phone-side camera app (QQ 相机) can advertise these types on an existing stream.
        // None may map onto the voice channel (PHONE/ASSISTANT) the way upstream did, or the head
        // unit would switch the output to channel 12 while the steering volume keys kept
        // controlling media.
        listOf("speechrecognition", "speechRecognition", "default", "compatibility").forEach {
            audioType ->
            val selection = AudioChannelMapper.map(
                audioType = audioType,
                payloadType = 102,
                mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            )
            assertEquals(AudioChannel.MEDIA, selection.channel)
            assertEquals(AudioContentType.MUSIC, selection.contentType)
        }
    }

    @Test
    fun automotiveMappingUsesTheBusSpecificCarPlayTypes() {
        listOf("media", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.MEDIA,
                contentType = AudioContentType.MUSIC,
            )
        }
        // CarPlay navigation prompt: "default" on the alternate (101) payload -> navigation.
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "default",
            payloadType = 101,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "alert",
            payloadType = 100,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
        )
    }

    @Test
    fun unknownTypesKeepTheMainHighAudioFallback() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "unknown",
            payloadType = AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "unknown",
            payloadType = 100,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
        )
    }

    @Test
    fun alertNeverFallsThroughToMediaOnAMainHighAudioSession() {
        // A CarPlay navigation prompt carried on the main-high-audio payload type (102) must still
        // keep the navigation bus in automotive mode — this is the regression the reorder fixes.
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "alert",
            payloadType = AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
        )
    }

    @Test
    fun navigationOverrideForcesAlertOntoTheChosenChannel() {
        val selection = AudioChannelMapper.map(
            audioType = "alert",
            payloadType = AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO,
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            navigationChannel = AudioChannelOverride.MEDIA,
        )
        assertEquals(AudioChannel.MEDIA, selection.channel)
        assertEquals(AudioContentType.MUSIC, selection.contentType)
    }

    @Test
    fun mediaOverrideForcesMediaOntoTheChosenChannel() {
        val selection = AudioChannelMapper.map(
            audioType = "media",
            payloadType = 100,
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            mediaChannel = AudioChannelOverride.NAVIGATION,
        )
        assertEquals(AudioChannel.NAVIGATION, selection.channel)
        assertEquals(AudioContentType.SPEECH, selection.contentType)
    }

    @Test
    fun autoOverrideKeepsTheAutomaticMapping() {
        val selection = AudioChannelMapper.map(
            audioType = "media",
            payloadType = 100,
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            navigationChannel = AudioChannelOverride.NAVIGATION,
            mediaChannel = AudioChannelOverride.AUTO,
        )
        assertEquals(AudioChannel.MEDIA, selection.channel)
    }

    @Test
    fun overrideDoesNotAffectTelephony() {
        val selection = AudioChannelMapper.map(
            audioType = "telephony",
            payloadType = 100,
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            navigationChannel = AudioChannelOverride.MEDIA,
            mediaChannel = AudioChannelOverride.MEDIA,
        )
        assertEquals(AudioChannel.PHONE, selection.channel)
    }

    @Test
    fun carPlayNaviPromptDefaultOnAlternatePayloadGoesToNavigationInBothModes() {
        // Real CarPlay capture: iPhone navigation prompts arrive as type=101 audioType="default"
        // (mono OPUS), music as type=102 audioType="media" (stereo AAC). Both mapping modes must
        // send the prompt to navigation and the music to media, otherwise the prompt is silent on
        // the media channel.
        for (mode in AudioChannelMappingMode.entries) {
            assertMapped(mode, "default", 101, AudioChannel.NAVIGATION, AudioContentType.SPEECH)
            assertMapped(mode, "media", 102, AudioChannel.MEDIA, AudioContentType.MUSIC)
            // "default" on the main media payload stays media.
            assertMapped(mode, "default", 102, AudioChannel.MEDIA, AudioContentType.MUSIC)
        }
    }

    private fun assertMapped(
        mode: AudioChannelMappingMode,
        audioType: String,
        payloadType: Int,
        channel: AudioChannel,
        contentType: AudioContentType,
    ) {
        assertEquals(
            AudioChannelSelection(channel, contentType),
            AudioChannelMapper.map(audioType, payloadType, mode),
        )
    }
}
