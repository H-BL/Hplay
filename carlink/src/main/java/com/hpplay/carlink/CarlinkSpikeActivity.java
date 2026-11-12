package com.hpplay.carlink;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ucarsink.sink.natives.SinkNative;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * P0 spike for the CarLink video path (libovmsink.so).
 *
 * <p>What we already proved: the .so loads in the Hplay process and {@code SinkNative.start()}
 * succeeds. What we learned from native logs: the .so is an RTSP <b>client</b> — {@code start()}'s
 * first arg is the phone IP and it connects OUT to {@code rtsp://<ip>:7236}.
 *
 * <p>This spike now stands up a loopback TCP server that plays the role of the "phone RTSP server",
 * points the sink at it, and captures the exact RTSP requests the sink emits (OPTIONS / M1
 * GET_PARAMETER / M3 SET_PARAMETER / M4 SETUP). That byte capture is the ground truth the
 * connection layer must satisfy.
 *
 * <p>We also replay a minimal standard WFD M1 response so the sink advances through its state
 * machine, and log how far it gets (M1→M3→M4→M5) and any video callbacks.
 */
public class CarlinkSpikeActivity extends Activity {
    private static final String TAG = "CarlinkSpike";
    private static final int FAKE_RTSP_PORT = 7236;

    private SurfaceView surfaceView;
    private TextView status;
    private volatile ServerSocket serverSocket;
    private volatile boolean running = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(13f);
        status.setPadding(24, 24, 24, 24);
        status.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        root.addView(status, lp);
        setContentView(root);

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {}
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {}
        });

        new Handler(Looper.getMainLooper()).postDelayed(this::run, 800);
    }

    private void run() {
        new Thread(this::spikeBody, "carlink-spike").start();
    }

    private void spikeBody() {
        try {
            log("=== 1) start fake phone RTSP server on 127.0.0.1:" + FAKE_RTSP_PORT + " ===");
            serverSocket = new ServerSocket(FAKE_RTSP_PORT, 4, InetAddress.getByName("127.0.0.1"));
            log("server listening: " + serverSocket.getLocalSocketAddress());

            log("=== 2) SinkNative.start(host=127.0.0.1, port=7236, 1280x720, dpi=240, fps=30) ===");
            boolean ok = SinkNative.start("127.0.0.1", FAKE_RTSP_PORT, 1280, 720,
                    /* dpi */ 240, /* fps */ 30, /* uibcEnc */ false,
                    /* lowLatency */ false, /* hevc */ false);
            log("SinkNative.start returned: " + ok + "  (sink now connects OUT to us)");

            log("=== 3) accept + capture the sink's RTSP requests ===");
            serverSocket.setSoTimeout(15000);
            try (Socket c = serverSocket.accept()) {
                log("sink CONNECTED from " + c.getRemoteSocketAddress());
                c.setSoTimeout(6000);
                InputStream in = c.getInputStream();
                OutputStream out = c.getOutputStream();

                // First try: does the sink send OPTIONS on its own? Read briefly.
                byte[] buf = new byte[8192];
                try {
                    int n = in.read(buf);
                    if (n > 0) {
                        log("RTSP<< (sink-initiated) " + new String(buf, 0, n, StandardCharsets.US_ASCII).replace("\r\n", " | "));
                    }
                } catch (java.net.SocketTimeoutException te) {
                    log("(no sink-initiated data in 6s -> sink likely waits for source M1)");
                }

                // Proactive: act as the WFD source and send M1 GET_PARAMETER request.
                String m1 = "GET_PARAMETER rtsp://127.0.0.1/wfd1.0/ RTSP/1.0\r\n"
                        + "CSeq: 1\r\n"
                        + "Content-Type: text/parameters\r\n"
                        + "Content-Length: 0\r\n\r\n";
                out.write(m1.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                log("RTSP>> (proactive M1 GET_PARAMETER sent)");

                // Read the sink's reply / any subsequent traffic, log raw bytes too.
                int rounds = 0;
                while (running && rounds < 10) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (java.net.SocketTimeoutException te) {
                        log("(read timeout, no more data)");
                        break;
                    }
                    if (n <= 0) break;
                    String msg = new String(buf, 0, n, StandardCharsets.US_ASCII);
                    log("RTSP<< " + msg.replace("\r\n", " | ").trim());
                    log("HEX<< " + hex(buf, Math.min(n, 48)));

                    String resp = buildResponse(msg);
                    if (resp != null) {
                        out.write(resp.getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        log("RTSP>> " + resp.split("\r\n")[0]);
                    }
                    rounds++;
                }
            }
            log("=== capture done ===");
        } catch (Throwable t) {
            Log.e(TAG, "spike error", t);
            log("EXCEPTION: " + t);
        }
    }

    /** Minimal standard-WFD canned responses so the sink's RTSP state machine advances. */
    private String buildResponse(String req) {
        String first = req.split("\r\n", 2)[0];
        String cseq = "1";
        for (String line : req.split("\r\n")) {
            if (line.toLowerCase().startsWith("cseq:")) {
                cseq = line.substring(5).trim();
            }
        }
        if (first.startsWith("OPTIONS")) {
            return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n"
                    + "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n\r\n";
        }
        if (first.contains("GET_PARAMETER")) {
            // Reply with a minimal but valid wfd_video_formats / audio / uibc set.
            String body = "wfd_video_formats: 00 00 02 02 00000020 00000000 00000000 00 0000 0000 00 none none\r\n"
                    + "wfd_audio_codecs: LPCM 00000002 00\r\n"
                    + "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 15550 0 mode=play\r\n"
                    + "wfd_uibc_capability: input_category_list=none\r\n"
                    + "wfd_content_protection: none\r\n"
                    + "wfd_presentation_URL: rtsp://127.0.0.1:7236/wfd1.0/streamid=0 none\r\n";
            return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n"
                    + "Content-Type: text/parameters\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.US_ASCII).length + "\r\n\r\n" + body;
        }
        if (first.startsWith("SET_PARAMETER")) {
            return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
        }
        if (first.startsWith("SETUP")) {
            return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n"
                    + "Session: 12345678;timeout=30\r\n"
                    + "Transport: RTP/AVP/UDP;unicast;client_port=15550\r\n\r\n";
        }
        if (first.startsWith("PLAY")) {
            return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nSession: 12345678\r\n\r\n";
        }
        // Unknown -> generic OK so we keep reading.
        return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
    }

    @Override
    protected void onDestroy() {
        running = false;
        try { SinkNative.stop("127.0.0.1"); } catch (Throwable ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        runOnUiThread(() -> status.setText(msg + "\n" + status.getText()));
    }

    private static String hex(byte[] d, int n) {
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", d[i] & 0xff));
            if (i < n - 1) sb.append(' ');
        }
        return sb.toString();
    }
}
