package com.hpplay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.hpplay.network.ForceFiveGhzHotspotManager
import java.util.concurrent.atomic.AtomicLong

/**
 * Starts the CarPlay host after boot when the user has enabled the startup option.
 *
 * Two things on this head unit make a plain BOOT_COMPLETED receiver unreliable:
 *
 * 1. It is an MTK IPO (in-place-off / quick-boot) unit. A normal ignition resumes from a frozen
 *    RAM image and the vendor announces readiness with its own broadcasts ([ACTION_BOOT_HU] /
 *    [ACTION_BOOT_IPO]) instead of the standard BOOT_COMPLETED. Every stock app registers for that
 *    whole family, so we do too; listening to BOOT_COMPLETED alone missed the IPO path.
 * 2. The stock launcher runs a "boot_startup" pass that re-launches itself several seconds after
 *    boot and pulls the foreground back. Launching CarPlay the instant the boot broadcast arrives
 *    wins the first frame but is then covered by the launcher, so the car ends up on its home
 *    screen. We therefore schedule the launch a few seconds later, once the launcher has settled.
 *
 * The launch is scheduled through [AlarmManager] so it still fires if this short-lived receiver
 * process is killed in the meantime. [claimBootHandling] collapses the burst of boot broadcasts
 * down to a single launch.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return
        Log.i(TAG, "Boot signal received: $action")

        if (!claimBootHandling()) {
            Log.i(TAG, "Boot signal ignored: this boot was already handled")
            return
        }

        // Prepare the forced 5 GHz hotspot first. It only makes the radio able to use 5 GHz; it does
        // not raise the hotspot, so the car is not left without Wi-Fi.
        if (AirPlayPersistence.loadForce5GhzHotspot(context)) {
            val appContext = context.applicationContext
            Thread {
                // Honour the channel the user picked in settings instead of leaving whatever an
                // earlier run wrote into the framework configuration (the old hard-coded 149).
                val manager = ForceFiveGhzHotspotManager(
                    appContext,
                    AirPlayPersistence.loadHotspotChannel(appContext).takeIf { it > 0 } ?: 0,
                )
                try {
                    val snapshot = manager.prepare()
                    Log.i(
                        TAG,
                        "5 GHz hotspot prepare: ${snapshot.stage} (${snapshot.detail}); " +
                            "channel requested=${snapshot.requestedChannel} " +
                            "observed=${snapshot.observedChannel ?: "unknown"} " +
                            "note=${snapshot.channelNote ?: "-"}",
                    )
                } catch (error: Throwable) {
                    Log.w(TAG, "5 GHz hotspot prepare failed", error)
                } finally {
                    manager.close()
                }
            }.start()
        }

        if (!AirPlayPersistence.loadAutoStartOnBoot(context)) {
            Log.i(TAG, "Boot auto-start is disabled; not launching")
            return
        }

        scheduleLaunch(context.applicationContext)
    }

    /**
     * Re-asserts the CarPlay activity at several points after boot.
     *
     * The stock launcher runs a "boot_startup" pass that re-launches itself a few seconds after boot
     * and pulls the foreground back; how late it does so varies by unit (about 6 s on the F510C,
     * about 16 s on the 24S). A single launch therefore sometimes loses the foreground again. We
     * fire a short series of launches through [AlarmManager] so the last one lands after the
     * launcher has settled. Bringing an already-foreground activity forward is a no-op
     * (singleTask + SINGLE_TOP just re-delivers the intent and maybeStartCarPlay bails out while a
     * controller exists), so the extra pulses never restart or flicker a running session.
     */
    private fun scheduleLaunch(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (alarms == null) {
            // No alarm service: fall back to starting right away rather than not at all.
            Log.w(TAG, "AlarmManager unavailable; launching immediately")
            launchNow(context)
            return
        }
        val now = SystemClock.elapsedRealtime()
        LAUNCH_DELAYS_MS.forEachIndexed { index, delayMs ->
            val launch = launchIntent(context)
            val pending = PendingIntent.getActivity(
                context,
                LAUNCH_REQUEST_CODE_BASE + index,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + delayMs, pending)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Boot auto-start scheduling failed at +${delayMs} ms", error)
            }
        }
        Log.i(TAG, "Boot auto-start scheduled at ${LAUNCH_DELAYS_MS.joinToString()} ms")
    }

    private fun launchNow(context: Context) {
        try {
            context.startActivity(launchIntent(context))
        } catch (error: RuntimeException) {
            Log.w(TAG, "Boot auto-start could not launch the host activity", error)
        }
    }

    /**
     * Intent for the host activity of the currently chosen protocol (CarPlay or CarLife), so a boot
     * with CarLife selected starts CarLife rather than CarPlay.
     */
    private fun launchIntent(context: Context): Intent {
        val protocol = AirPlayPersistence.loadProtocol(context)
        val component = ProtocolRouter.resolveProtocolComponent(context, protocol)
        return Intent().apply {
            this.component = component
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
    }

    /** True for the first boot broadcast of a boot, false for any repeats that follow quickly. */
    private fun claimBootHandling(): Boolean {
        // Wall clock, not elapsedRealtime: an IPO resume keeps the frozen process (and its statics)
        // alive, and we need the window to have expired after the car sat off for a while. The unit's
        // RTC is battery-backed and correct, and every broadcast of one boot arrives within seconds,
        // so a one-minute window collapses the burst without ever swallowing the next ignition.
        val now = System.currentTimeMillis()
        while (true) {
            val last = lastHandledAt.get()
            if (last != 0L && now - last in 0 until DEDUPE_WINDOW_MS) return false
            if (lastHandledAt.compareAndSet(last, now)) return true
        }
    }

    private companion object {
        const val TAG = "hplay-boot"

        /** Broadcasts that can announce a boot on this unit; any one of them is enough. */
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_BOOT_HU,
            ACTION_BOOT_IPO,
            ACTION_QUICKBOOT_POWERON,
        )

        /** Long enough to swallow the whole burst of boot broadcasts, short enough to allow a reboot. */
        const val DEDUPE_WINDOW_MS = 60_000L

        /**
         * Launch points after the boot broadcast, in milliseconds. Spread past the stock launcher's
         * "boot_startup" re-assert, which settles around 6 s on the F510C and 16 s on the 24S.
         */
        val LAUNCH_DELAYS_MS = longArrayOf(5_000L, 11_000L, 17_000L, 23_000L)

        const val LAUNCH_REQUEST_CODE_BASE = 0x4842

        val lastHandledAt = AtomicLong(0L)
    }
}

/** Vendor "head unit boot finished" broadcast (SGMW/五菱). */
private const val ACTION_BOOT_HU = "android.intent.action.ACTION_BOOT_HU"

/** MTK IPO quick-boot resume broadcast; this unit resumes from a frozen RAM image on ignition. */
private const val ACTION_BOOT_IPO = "android.intent.action.ACTION_BOOT_IPO"

/** Quick-boot resume broadcast used by several OEM launchers on this platform. */
private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
