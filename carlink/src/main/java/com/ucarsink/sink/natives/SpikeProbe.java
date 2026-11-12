package com.ucarsink.sink.natives;

import android.util.Log;

/**
 * Lightweight probe used by the spike to characterise the native frame stream without pulling in a
 * full decoder yet: it logs the first N frames and reports the observed codec enum / sizes / pts so
 * we can lock down the callback contract before wiring AndroidMediaSink.
 */
public final class SpikeProbe {
    private static final String TAG = "SinkProbe";
    private static final int LOG_LIMIT = 30;
    private static volatile int videoCount;
    private static volatile int audioCount;

    private SpikeProbe() {
    }

    static void onVideo(String dev, int codec, int w, int h, byte[] data, long pts) {
        int n = ++videoCount;
        if (n <= LOG_LIMIT) {
            String head = data == null ? "null" : hexHead(data, 24);
            Log.i(TAG, "VIDEO#" + n + " dev=" + dev + " codec=" + codec + " " + w + "x" + h
                    + " len=" + (data == null ? -1 : data.length) + " pts=" + pts
                    + " annexB=" + (data != null && looksAnnexB(data)) + " head=" + head);
        } else if (n == LOG_LIMIT + 1) {
            Log.i(TAG, "VIDEO# ... further frames suppressed");
        }
    }

    static void onAudio(String dev, int type, int len, byte[] data) {
        int n = ++audioCount;
        if (n <= LOG_LIMIT) {
            Log.i(TAG, "AUDIO#" + n + " dev=" + dev + " type=" + type + " len=" + len
                    + " actual=" + (data == null ? -1 : data.length));
        }
    }

    private static boolean looksAnnexB(byte[] d) {
        if (d.length >= 4) {
            return d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1;
        }
        if (d.length >= 3) {
            return d[0] == 0 && d[1] == 0 && d[2] == 1;
        }
        return false;
    }

    private static String hexHead(byte[] d, int max) {
        int n = Math.min(d.length, max);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", d[i] & 0xff));
            if (i < n - 1) sb.append(' ');
        }
        return sb.toString();
    }
}
