package com.ucarsink.sink.natives;

/**
 * Receives the demuxed stream callbacks from libovmsink.so (via [SinkNative]).
 *
 * <p>These map 1:1 to the native {@code onNative*} callbacks. Frames arrive as already-demuxed
 * encoded access units (H.264/H.265 Annex-B) plus a pts; the consumer decodes them.
 */
public interface CarlinkSinkListener {
    default void onSinkStarted(String session) {}
    default void onSinkStopped(String session) {}
    default void onUibcEncrypted(String session, boolean enabled) {}

    /** Called once before frames, with the negotiated mime (e.g. "video/avc") and size. */
    default void onVideoInitialized(String dev, String mime, int width, int height) {}

    default void onAudioInitialized(String dev, String mime, int sampleRate, int channels) {}

    /** One encoded video access unit. {@code codec}: 0=avc/1=hevc (observed via SpikeProbe). */
    default void onVideoData(String dev, int codec, int width, int height, byte[] data, long pts) {}

    default void onAudioData(String dev, int type, int len, byte[] data) {}
}
