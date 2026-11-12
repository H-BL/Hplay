package com.ucarsink.sink.natives;

import android.util.Log;

/**
 * Facade for the native Wi-Fi Display sink (libovmsink.so).
 *
 * <p>IMPORTANT — the class name and package are fixed: libovmsink.so resolves its JNI bindings via
 * {@code RegisterNatives} against {@code com/ucarsink/sink/natives/SinkNative}. Signatures verified
 * against the .so string table:
 *
 * <pre>
 *   start        (Ljava/lang/String;IIIIIZZZ)Z
 *   stop/pause/resume (Ljava/lang/String;)Z
 *   setParameter (Ljava/lang/String;Ljava/util/HashMap;)Z
 *   onUibcEvent  (Ljava/lang/String;[B)Z
 *   onNativeVideoDataReceived  (Ljava/lang/String;II[BJ)V
 *   onNativeAudioDataReceived  (Ljava/lang/String;II[B)V
 *   onNativeWfdSinkStarted/Stopped (Ljava/lang/String;)V
 *   onUibcEncrypted (Ljava/lang/String;Z)V
 *   onNativeCastVideoInitialized / onNativeCastAudioInitialized (Ljava/lang/String;Ljava/lang/String;II)V
 * </pre>
 *
 * <p>SEMANTICS OF start() (verified by native log: {@code start WifiDisplaySink,host:<h>:<port>,
 * <w>:<h>, dpi:<dpi>, fps:<fps>}): the .so is an RTSP <b>client</b> (AOSP WifiDisplaySink /
 * RTSPClient). Its first argument is the <b>source host</b> (the phone's IP), NOT a local session
 * id — it CONNECTS OUT to {@code rtsp://<host>:<port>}.
 */
public class SinkNative {
    private static final String TAG = "SinkNative";

    static {
        try {
            System.loadLibrary("ovmsink");
            Log.i(TAG, "loaded libovmsink.so OK");
        } catch (Throwable t) {
            Log.e(TAG, "loadLibrary(ovmsink) FAILED", t);
            throw t;
        }
    }

    private static volatile CarlinkSinkListener mListener;

    private SinkNative() {
    }

    public static void registerListener(CarlinkSinkListener listener) {
        mListener = listener;
    }

    public static void unregisterListener() {
        mListener = null;
    }

    // ---- native entry points (signatures MUST match the .so RegisterNatives table) ----

    /**
     * @param host       phone IP (RTSP source host) — the sink connects OUT to rtsp://host:port
     * @param rtspPort   phone RTSP port (7236)
     * @param width      negotiated video width
     * @param height     negotiated video height
     * @param dpi        display dpi (native log label "dpi")
     * @param fps        frame rate (native log label "fps")
     */
    public static native boolean start(String host, int rtspPort, int width, int height,
                                       int dpi, int fps, boolean uibcEnc,
                                       boolean lowLatency, boolean hevc);

    public static native boolean stop(String host);

    public static native boolean pause(String host);

    public static native boolean resume(String host);

    public static native boolean setParameter(String host, java.util.HashMap<String, String> params);

    public static native boolean onUibcEvent(String host, byte[] event);

    // ---- callbacks invoked BY the .so ----

    static void onNativeWfdSinkStarted(String session) {
        Log.i(TAG, "onNativeWfdSinkStarted session=" + session);
        CarlinkSinkListener l = mListener;
        if (l != null) l.onSinkStarted(session);
    }

    static void onNativeWfdSinkStopped(String session) {
        Log.i(TAG, "onNativeWfdSinkStopped session=" + session);
        CarlinkSinkListener l = mListener;
        if (l != null) l.onSinkStopped(session);
    }

    static void onUibcEncrypted(String session, boolean enabled) {
        Log.i(TAG, "onUibcEncrypted session=" + session + " enabled=" + enabled);
        CarlinkSinkListener l = mListener;
        if (l != null) l.onUibcEncrypted(session, enabled);
    }

    static void onNativeCastVideoInitialized(String dev, String mime, int w, int h) {
        Log.i(TAG, "onNativeCastVideoInitialized dev=" + dev + " mime=" + mime + " " + w + "x" + h);
        CarlinkSinkListener l = mListener;
        if (l != null) l.onVideoInitialized(dev, mime, w, h);
    }

    static void onNativeCastAudioInitialized(String dev, String mime, int sampleRate, int channels) {
        Log.i(TAG, "onNativeCastAudioInitialized dev=" + dev + " mime=" + mime + " sr=" + sampleRate + " ch=" + channels);
        CarlinkSinkListener l = mListener;
        if (l != null) l.onAudioInitialized(dev, mime, sampleRate, channels);
    }

    static void onNativeVideoDataReceived(String dev, int codec, int w, int h, byte[] data, long pts) {
        CarlinkSinkListener l = mListener;
        if (l != null) l.onVideoData(dev, codec, w, h, data, pts);
    }

    static void onNativeAudioDataReceived(String dev, int type, int len, byte[] data) {
        CarlinkSinkListener l = mListener;
        if (l != null) l.onAudioData(dev, type, len, data);
    }
}
