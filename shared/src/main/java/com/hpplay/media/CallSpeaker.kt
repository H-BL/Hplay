package com.hpplay.media

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Puts a call on the built-in loudspeaker while it is in communication mode. On a phone or tablet
 * used as the head unit, communication mode otherwise plays on the earpiece and records with the
 * handset microphone, so a CarPlay call is heard only close to the screen. Android 12+ picks the
 * communication device; older versions use the speakerphone flag.
 */
internal class CallSpeaker private constructor(private val undo: () -> Unit) {
    fun restore() {
        runCatching(undo)
    }

    companion object {
        /** Null when this device has no loudspeaker for calls or refused it; the call keeps its route. */
        fun on(manager: AudioManager, sdk: Int = Build.VERSION.SDK_INT): CallSpeaker? = runCatching {
            if (sdk >= Build.VERSION_CODES.S) {
                val speaker = manager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return null
                if (!manager.setCommunicationDevice(speaker)) return null
                CallSpeaker { manager.clearCommunicationDevice() }
            } else {
                @Suppress("DEPRECATION")
                val before = manager.isSpeakerphoneOn
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = true
                @Suppress("DEPRECATION")
                CallSpeaker { manager.isSpeakerphoneOn = before }
            }
        }.getOrNull()
    }
}
