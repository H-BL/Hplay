package com.hpplay.media

/**
 * Bluetooth-audio ("experimental") routing decision.
 *
 * When the user enables Bluetooth audio output, CarPlay media audio is produced by the phone's own
 * Bluetooth A2DP link instead of this head unit. The phone is not told anything, so it keeps sending
 * the media stream over Wi-Fi/CarPlay; the head unit must drop it locally to avoid a doubled,
 * delayed second copy on the car speakers. Calls, navigation prompts and Siri keep the CarPlay path.
 */
internal const val CARPLAY_MEDIA_AUDIO_TYPE = "media"

internal fun shouldSuppressCarPlayAudio(
    bluetoothAudioOutput: Boolean,
    audioType: String,
): Boolean = bluetoothAudioOutput && audioType.lowercase() == CARPLAY_MEDIA_AUDIO_TYPE
