package com.hpplay.network

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Brings up the head unit's own wireless ADB (loopback `adbd`) from inside the app, so the
 * "force 5 GHz hotspot" flow can reach a root shell without Magisk's `su`.
 *
 * On the F510C (and sibling Desaysv head units) the ROM ships a vendor init rule that turns the
 * property `sys.wifiadb.enable` into a listening `adbd`:
 *
 * ```
 * on property:sys.wifiadb.enable=true
 *     setprop service.adb.tcp.port 5557
 *     restart adbd
 * on property:sys.wifiadb.enable=false
 *     setprop service.adb.tcp.port 0
 *     restart adbd
 * ```
 *
 * The catch is that this rule only fires when the property *changes*, and the ROM re-writes
 * `sys.wifiadb.enable=false` a few seconds after boot, so a plain "set it to true" does nothing
 * once the boot sweep has run. The reliable re-arm is therefore `false` then `true`, which the
 * property service sees as two changes.
 *
 * This ROM permits an ordinary app process to write these properties, so the whole thing is a
 * handful of `setprop` calls run through `/system/bin/sh` — no root, no `su`, and nothing to
 * install. Everything is best-effort: a head unit that forbids the writes simply stays as it was,
 * and the caller falls back to the existing "no local ADB channel" path.
 */
object WirelessAdbEnabler {
    private const val TAG = "hplay-wireless-adb"

    /**
     * Port the vendor rule configures, and the one [AdbLocalClient] probes first.
     */
    const val PORT = 5557

    /** How long to wait for `restart adbd` to come back before trying the fallback / giving up. */
    private const val READY_TIMEOUT_MILLIS = 12_000L

    /** Gap between readiness polls while adbd restarts. */
    private const val READY_POLL_MILLIS = 500L

    private val running = AtomicBoolean(false)

    /**
     * Makes sure a root `adbd` is listening on [PORT], spawning a background thread so the caller
     * never blocks the UI. Re-entrant calls while a run is in flight are ignored.
     *
     * @param requireRoot when true, also asks adbd to run as `uid 0`, which the 5 GHz flow needs.
     * @return a future-free handle: the work continues on its own thread.
     */
    fun ensureEnabled(context: Context, requireRoot: Boolean = true) {
        if (!running.compareAndSet(false, true)) return
        Thread({ runCatching { enableBlocking(context, requireRoot) } }, "wireless-adb-enabler")
            .apply { isDaemon = true }
            .start()
    }

    /**
     * The blocking body of [ensureEnabled]; returns the final state so a caller (or a test) can
     * inspect it without the UI plumbing.
     */
    fun enableBlocking(context: Context, requireRoot: Boolean = true): State {
        try {
            val before = readState()
            Log.i(TAG, "wireless ADB before: $before")
            if (before.ready && (!requireRoot || before.root)) {
                Log.i(TAG, "wireless ADB already up (port=${before.port}, root=${before.root})")
                return before
            }
            // Escalate to a root adbd before touching the vendor rule: the rule's `restart adbd`
            // then starts the daemon as uid 0, which is what the 5 GHz flow's shell commands need.
            if (requireRoot && !before.root) exec(WirelessAdbCommands.setRoot())
            // Re-arm the vendor rule. When the channel is already up but merely lacks root, the
            // false->true flip is what restarts adbd so it picks up service.adb.root.
            for (command in WirelessAdbCommands.reArm()) exec(command)
            var after = awaitReady()
            if (!after.ready) {
                Log.w(TAG, "vendor wifiadb rule did not take; applying the direct fallback")
                for (command in WirelessAdbCommands.fallback()) exec(command)
                after = awaitReady()
            }
            Log.i(TAG, "wireless ADB after: $after")
            return after
        } finally {
            running.set(false)
        }
    }

    /**
     * Polls until adbd is listening again (or the timeout elapses).
     *
     * `restart adbd` closes the socket and rebinds asynchronously, so a fixed sleep either reports a
     * false "not ready" (and needlessly falls through to the fallback) or waits longer than needed.
     * Polling converges in a few hundred milliseconds in the common case.
     */
    private fun awaitReady(): State {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS
        var last = readState()
        while (System.currentTimeMillis() < deadline) {
            if (last.ready) return last
            sleep(READY_POLL_MILLIS)
            last = readState()
        }
        return last
    }

    /** The most recent observed state, without running anything. Cheap enough for a status line. */
    fun readState(): State {
        val port = exec(WirelessAdbCommands.readProp(WirelessAdbCommands.ADB_TCP_PORT_PROP))
            .trim().toIntOrNull() ?: -1
        val adbd = exec(WirelessAdbCommands.readProp(WirelessAdbCommands.ADBD_SVC_PROP)).trim()
        val root = exec(WirelessAdbCommands.readProp(WirelessAdbCommands.ADB_ROOT_PROP)).trim() == "1"
        return State(port = port, adbdRunning = adbd == "running", root = root)
    }

    /**
     * Runs [command] through `sh` and returns its combined stdout/stderr.
     *
     * `setprop`/`getprop` live in `/system/bin` (toybox/toolbox), and the app process may write
     * the `sys.`/`service.adb.` properties on this ROM, so a plain shell is enough. Output is
     * capped and the process is killed on timeout so a wedged command cannot leak.
     */
    private fun exec(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Log.w(TAG, "command timed out: $command")
        }
        output.take(MAX_OUTPUT_CHARS)
    } catch (error: IOException) {
        Log.w(TAG, "command failed: $command", error)
        ""
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Snapshot of the wireless-ADB channel. */
    data class State(
        val port: Int,
        val adbdRunning: Boolean,
        val root: Boolean,
    ) {
        /** True when adbd is listening on the port the vendor rule configures. */
        val ready: Boolean get() = port == PORT && adbdRunning
    }

    private const val COMMAND_TIMEOUT_SECONDS = 5L
    private const val MAX_OUTPUT_CHARS = 4 * 1024
}

/**
 * The shell vocabulary [WirelessAdbEnabler] speaks. Kept free of Android types so the exact bytes
 * can be asserted in a plain unit test — a typo here only shows up on a car, where the failure
 * mode is "the ADB channel silently never comes up".
 */
object WirelessAdbCommands {
    /** Vendor property whose change drives the init rule that raises loopback adbd. */
    const val WIFIADB_PROP = "sys.wifiadb.enable"

    /** Property the vendor rule sets to the listening port; the only trustworthy readiness signal. */
    const val ADB_TCP_PORT_PROP = "service.adb.tcp.port"

    /** `init` service state, used to confirm adbd is actually running (not just configured). */
    const val ADBD_SVC_PROP = "init.svc.adbd"

    /** adbd's own "run as root" flag, read by the daemon at start-up. */
    const val ADB_ROOT_PROP = "service.adb.root"

    fun readProp(name: String): String = "getprop $name"

    fun setProp(name: String, value: String): String = "setprop $name $value"

    /** Asks the next adbd start to run as `uid 0` (the 5 GHz flow's shell needs it). */
    fun setRoot(): String = setProp(ADB_ROOT_PROP, "1")

    /**
     * Re-arms the vendor rule.
     *
     * `init` only reacts to a *change*, and the ROM rewrites the property to `false` shortly after
     * boot, so `false` then `true` is what actually restarts adbd on the configured port.
     */
    fun reArm(): List<String> = listOf(
        setProp(WIFIADB_PROP, "false"),
        setProp(WIFIADB_PROP, "true"),
    )

    /**
     * The generic fallback when the vendor rule does not take.
     *
     * Only [ADB_TCP_PORT_PROP] is trusted for readiness — `persist.adb.tcp.port` /
     * `persist.adb.tcpip` are often left at 5557 by other tooling even when nothing is listening,
     * so they are written here only to help the daemon, never read back as proof.
     */
    fun fallback(): List<String> = listOf(
        "settings put global adb_enabled 1",
        "settings put global adb_wifi_enabled 1",
        setProp(ADB_TCP_PORT_PROP, WirelessAdbEnabler.PORT.toString()),
        setProp("persist.adb.tcp.port", WirelessAdbEnabler.PORT.toString()),
        setProp("persist.adb.tcpip", WirelessAdbEnabler.PORT.toString()),
        "setprop ctl.restart adbd",
    )
}
