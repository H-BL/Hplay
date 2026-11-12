package com.hpplay.media

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneSourceTest {
    private val mic = MediaRecorder.AudioSource.MIC
    private val voiceCommunication = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private val voiceRecognition = MediaRecorder.AudioSource.VOICE_RECOGNITION

    @Test
    fun normalCallUsesVoiceCommunicationForEchoCancellation() {
        assertEquals(
            voiceCommunication,
            microphoneSourceFor("telephony", speakerphoneCall = false, carPhoneState = false, geelyHeadUnit = false),
        )
    }

    @Test
    fun speakerphoneCallUsesPlainMicToKeepLoudspeakerAudio() {
        assertEquals(
            mic,
            microphoneSourceFor("telephony", speakerphoneCall = true, carPhoneState = false, geelyHeadUnit = false),
        )
    }

    @Test
    fun carPhoneStateUsesVoiceRecognitionUnlessGeely() {
        assertEquals(
            voiceRecognition,
            microphoneSourceFor("telephony", speakerphoneCall = false, carPhoneState = true, geelyHeadUnit = false),
        )
        assertEquals(
            voiceCommunication,
            microphoneSourceFor("telephony", speakerphoneCall = false, carPhoneState = true, geelyHeadUnit = true),
        )
    }

    @Test
    fun siriUsesVoiceRecognitionExceptOnGeely() {
        assertEquals(
            voiceRecognition,
            microphoneSourceFor("speechrecognition", speakerphoneCall = false, carPhoneState = false, geelyHeadUnit = false),
        )
        assertEquals(
            voiceCommunication,
            microphoneSourceFor("speechrecognition", speakerphoneCall = false, carPhoneState = false, geelyHeadUnit = true),
        )
    }

    @Test
    fun mediaUsesPlainMic() {
        assertEquals(
            mic,
            microphoneSourceFor("media", speakerphoneCall = false, carPhoneState = false, geelyHeadUnit = false),
        )
    }

    @Test
    fun onlyCallsSwitchTheDeviceIntoCommunicationMode() {
        assertTrue(microphoneNeedsCommunicationMode("telephony"))
        // A voice memo (WeChat/QQ) or Siri must record in the head unit's normal mode; switching
        // it into communication mode rerouted the car audio and silenced the recording.
        assertEquals(false, microphoneNeedsCommunicationMode("default"))
        assertEquals(false, microphoneNeedsCommunicationMode("media"))
        assertEquals(false, microphoneNeedsCommunicationMode("speechrecognition"))
    }

    @Test
    fun sourceNamesAreReadableInTheSessionLog() {
        assertEquals("MIC", sourceName(MediaRecorder.AudioSource.MIC))
        assertEquals("VOICE_RECOGNITION", sourceName(MediaRecorder.AudioSource.VOICE_RECOGNITION))
        assertEquals("VOICE_COMMUNICATION", sourceName(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
        assertEquals("SOURCE_1234", sourceName(1234))
    }
}
