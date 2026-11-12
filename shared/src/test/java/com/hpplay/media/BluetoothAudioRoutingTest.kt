package com.hpplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAudioRoutingTest {
    @Test
    fun suppressesMediaWhenEnabled() {
        assertTrue(shouldSuppressCarPlayAudio(bluetoothAudioOutput = true, audioType = "media"))
        assertTrue(shouldSuppressCarPlayAudio(bluetoothAudioOutput = true, audioType = "Media"))
    }

    @Test
    fun keepsCallsNavigationAndSiriOnWifi() {
        assertFalse(shouldSuppressCarPlayAudio(bluetoothAudioOutput = true, audioType = "telephony"))
        assertFalse(shouldSuppressCarPlayAudio(bluetoothAudioOutput = true, audioType = "alert"))
        assertFalse(
            shouldSuppressCarPlayAudio(bluetoothAudioOutput = true, audioType = "speechrecognition"),
        )
        assertFalse(shouldSuppressCarPlayAudio(bluetoothAudioOutput = true, audioType = "default"))
    }

    @Test
    fun neverSuppressesWhenDisabled() {
        assertFalse(shouldSuppressCarPlayAudio(bluetoothAudioOutput = false, audioType = "media"))
    }
}
