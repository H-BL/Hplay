package com.hpplay.media

internal enum class AudioChannelMappingMode {
    MOBILE_COMPATIBLE,
    AUTOMOTIVE_BUS,
}

internal enum class AudioChannel {
    MEDIA,
    PHONE,
    ASSISTANT,
    NAVIGATION,
}

internal enum class AudioContentType {
    MUSIC,
    SPEECH,
}

internal data class AudioChannelSelection(
    val channel: AudioChannel,
    val contentType: AudioContentType,
)

/**
 * A user-selected physical channel for one class of CarPlay stream.
 *
 * The automatic mapping decides a channel from the CarPlay audio type, but on a head unit whose
 * audio policy does not route the navigation channel the way the app expects, a driver needs to
 * point a class of audio at a specific bus themselves. [AUTO] keeps the automatic behaviour; the
 * other values force every stream of that class onto the chosen channel.
 *
 * Public (not internal) on purpose: the settings activity lives in :common and has to hand the
 * choice to :shared.
 */
enum class AudioChannelOverride {
    AUTO,
    MEDIA,
    NAVIGATION,
    ASSISTANT,
    PHONE,
    ;

    internal fun toChannelOrNull(): AudioChannel? = when (this) {
        AUTO -> null
        MEDIA -> AudioChannel.MEDIA
        NAVIGATION -> AudioChannel.NAVIGATION
        ASSISTANT -> AudioChannel.ASSISTANT
        PHONE -> AudioChannel.PHONE
    }

    companion object {
        fun fromStorageKey(key: String?): AudioChannelOverride =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: AUTO
    }
}

/**
 * Maps CarPlay stream metadata to Android AudioAttributes values.
 *
 * AAOS routes AudioTrack instances by usage, so the automotive mode keeps generic media streams
 * on the media bus instead of reusing mobile's navigation fallback for default/compatibility.
 *
 * [navigationChannel] / [mediaChannel] are the user's optional manual overrides. Navigation-class
 * audio ("alert") honours [navigationChannel]; media-class audio ("media"/"default"/
 * "compatibility") honours [mediaChannel]. [AudioChannelOverride.AUTO] keeps the built-in mapping.
 */
internal object AudioChannelMapper {
    const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102

    fun map(
        audioType: String,
        payloadType: Int,
        mode: AudioChannelMappingMode,
        navigationChannel: AudioChannelOverride = AudioChannelOverride.AUTO,
        mediaChannel: AudioChannelOverride = AudioChannelOverride.AUTO,
    ): AudioChannelSelection {
        val normalized = audioType.lowercase()
        val base = when (mode) {
            AudioChannelMappingMode.MOBILE_COMPATIBLE -> mapMobileCompatible(normalized, payloadType)
            AudioChannelMappingMode.AUTOMOTIVE_BUS -> mapAutomotiveBus(normalized, payloadType)
        }
        return applyOverride(base, normalized, navigationChannel, mediaChannel)
    }

    /**
     * Applies the user's per-class channel override on top of the automatic [selection].
     *
     * Navigation-class ("alert") audio follows [navigationChannel]; media-class ("media"/"default"/
     * "compatibility") audio follows [mediaChannel]. Any other type, or an [AudioChannelOverride.AUTO]
     * choice, keeps the automatic result.
     */
    private fun applyOverride(
        selection: AudioChannelSelection,
        audioType: String,
        navigationChannel: AudioChannelOverride,
        mediaChannel: AudioChannelOverride,
    ): AudioChannelSelection {
        val override = when (audioType) {
            // "alert" and "default" are the two CarPlay navigation audio types.
            "alert", "default" -> navigationChannel
            "media", "compatibility" -> mediaChannel
            else -> AudioChannelOverride.AUTO
        }
        val channel = override.toChannelOrNull() ?: return selection
        val contentType = if (channel == AudioChannel.MEDIA) {
            AudioContentType.MUSIC
        } else {
            AudioContentType.SPEECH
        }
        return AudioChannelSelection(channel, contentType)
    }

    private fun mapMobileCompatible(
        audioType: String,
        payloadType: Int,
    ): AudioChannelSelection = when (audioType) {
        // Only a real phone call keeps the dedicated voice channel, so the head unit's call
        // volume / echo cancellation still work.
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        // Navigation prompts keep their own channel so they can duck the music instead of
        // taking it over.
        "alert" -> AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        // CarPlay delivers a navigation app's spoken prompts as the "default" audio type on the
        // alternate (101) payload, while music arrives as "media" on the main-high-audio (102)
        // payload. Route "default" to the navigation bus unless it is on the main media payload;
        // otherwise an iPhone navigation prompt would play on the media channel.
        "default" -> if (payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        } else {
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        }
        // "media" and "compatibility" (and unknown types) stay on the media channel.
        else -> AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
    }

    private fun mapAutomotiveBus(
        audioType: String,
        payloadType: Int,
    ): AudioChannelSelection = when (audioType) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        // Navigation first: a CarPlay navigation prompt must keep the navigation bus regardless of
        // its payload type. Keeping this ahead of the generic branch means a "alert" stream can
        // never fall through to media on a main-high-audio (102) session.
        "alert" -> AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        // A CarPlay navigation app's prompts arrive as the "default" audio type on the alternate
        // (101) payload; music arrives as "media" on the main-high-audio (102) payload. Route
        // "default" to navigation unless it is on the main media payload.
        "default" -> if (payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        } else {
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        }
        "media", "compatibility" ->
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        else -> mainHighAudioOrNavigation(payloadType)
    }

    private fun mainHighAudioOrNavigation(payloadType: Int): AudioChannelSelection =
        if (payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        } else {
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        }
}
