package com.hpplay.network

import android.util.Log
import java.io.IOException

/**
 * Root shell access for the F510C "force 5 GHz hotspot" flow.
 *
 * The commands cannot run in the app process — the Wi-Fi service checks a signature-level permission
 * an ordinary app can never hold — so they are sent from a root shell instead. That shell is reached
 * over the head unit's **own adbd on loopback**, not through Magisk: the car starts adbd as `uid 0`
 * on TCP 5557 and does not ask the host to authenticate, so an in-app adb client gets root without
 * `su`. This is what keeps the feature usable where Magisk's `su` is off limits, and it degrades
 * cleanly (everything fails closed) when no adb channel is available.
 *
 * The connection is cached because the flow issues a burst of commands, but a *transport* failure
 * drops the cache and retries once: adbd can restart or move ports between commands, and without the
 * retry a single dropped socket would leave the cache pointing at a dead connection for the rest of
 * the process's life.
 */
internal object RootShell {
    private const val TAG = "hplay-usb"

    @Volatile
    private var cached: AdbLocalClient? = null

    /** The loopback adb port that answered, or -1 when no channel is available. */
    fun port(): Int = try {
        client().probe()
    } catch (_: IOException) {
        -1
    }

    /** True when an adb channel is present and its shell actually grants uid 0. */
    fun isAvailable(): Boolean = try {
        run(FiveGhzHotspotCommands.probeRoot()).contains("uid=0")
    } catch (_: IOException) {
        false
    }

    /**
     * Runs [command] through the adb channel and returns its combined output.
     *
     * A transport failure (the socket died, or there is no channel yet) drops the cached connection
     * and retries once, so a transient adbd restart does not break the feature until the app
     * restarts. Command failures that would fail identically on a second attempt are not retried.
     *
     * @throws IOException when the channel is unavailable or the command cannot be run.
     */
    fun run(command: String): String = try {
        client().shell(command)
    } catch (error: IOException) {
        if (!AdbChannelRetry.isTransportFailure(error)) throw error
        Log.w(TAG, "adb channel dropped (${error.javaClass.simpleName}); reconnecting once")
        reset()
        client().shell(command)
    }

    /** Forgets the cached channel so the next call re-probes; used when the channel drops. */
    fun reset() {
        cached = null
    }

    private fun client(): AdbLocalClient {
        val current = cached ?: synchronized(this) {
            cached ?: AdbLocalClient().also { cached = it }
        }
        if (current.port <= 0 && current.probe() <= 0) {
            Log.w(TAG, "no adb channel on the head unit")
            throw IOException(AdbChannelRetry.NO_CHANNEL_MESSAGE)
        }
        return current
    }
}
