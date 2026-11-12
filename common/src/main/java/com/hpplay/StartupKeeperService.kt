package com.hpplay

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Re-asserts CarPlay after the head unit wakes from sleep, where no boot broadcast ever arrives.
 *
 * This unit does not truly power off when the car is switched off: most of the time it suspends to
 * RAM (sleep) and only sometimes powers down. A cold power-on sends BOOT_COMPLETED / the vendor
 * boot broadcasts that [BootReceiver] listens for, but a resume from sleep sends none of them — the
 * framework just resumes the frozen processes and turns the display back on. So when the car starts
 * again the CarPlay host is still sitting in the background and never comes back to the foreground.
 *
 * An accessibility service is one of the few components the system keeps bound and re-binds on its
 * own, so it is the reliable "is the system awake" hook here (it is already enabled on this unit for
 * the USB prompt auto-confirm, and it survives the suspend). We use three wake signals and act on
 * whichever fires first:
 *  - a runtime [Intent.ACTION_SCREEN_ON] / [Intent.ACTION_USER_PRESENT] receiver (the display just
 *    came back, the clearest "car switched on" edge),
 *  - [onServiceConnected] — if the system re-binds this service after the suspend, that itself means
 *    it is awake,
 *  - a [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] for any package other than ours (the stock
 *    launcher reclaiming the foreground on wake).
 *
 * As on a cold boot, the stock launcher re-asserts itself a few seconds after the screen comes back
 * and pulls the foreground away, so a single launch loses the race. [startPulseSeries] therefore
 * brings CarPlay forward several times over the first ~30 s, exactly like [BootReceiver] does for
 * boot; re-asserting an already-foreground singleTask activity is a no-op, so once CarPlay sticks
 * the remaining pulses do nothing.
 *
 * Every attempt funnels through [reviveOnce], which does nothing when the host is already the
 * foreground app ([HostVisibility]) and respects the user's auto-start setting. It never reads
 * window content.
 */
class StartupKeeperService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var screenReceiver: BroadcastReceiver? = null

    /** Until this elapsedRealtime, a launcher window change is treated as a wake artifact. */
    @Volatile private var wakeArmedUntil = 0L

    /** A pulse series is in flight; further wake signals must not start another one. */
    @Volatile private var pulseRunning = false

    /** Generation token so an older series' pending pulses stop if a newer series starts. */
    @Volatile private var pulseGeneration = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "startup keeper connected")
        registerScreenReceiver()
        // If the system re-bound us on resume, the host was not the foreground app and it is awake
        // now — bring CarPlay back.
        onWakeSignal("service-connected")
    }

    override fun onDestroy() {
        pulseGeneration += 1
        handler.removeCallbacksAndMessages(null)
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered; nothing to do.
            }
        }
        screenReceiver = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        // A window change only means "we just woke and the stock launcher reclaimed the foreground"
        // when it lands shortly after a real wake signal. Outside that armed window it is just the
        // user using the launcher, so we must not yank CarPlay back on top of them.
        if (SystemClock.elapsedRealtime() > wakeArmedUntil) return
        onWakeSignal("foreground=$pkg")
    }

    override fun onInterrupt() = Unit

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> onWakeSignal("screen-on")
                    Intent.ACTION_USER_PRESENT -> onWakeSignal("user-present")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            screenReceiver = receiver
        } catch (error: RuntimeException) {
            Log.w(TAG, "could not register screen-on receiver", error)
        }
    }

    /** One wake-up: fold the burst of wake signals into a single pulse series. */
    private fun onWakeSignal(reason: String) {
        val now = SystemClock.elapsedRealtime()
        wakeArmedUntil = now + WAKE_ARM_WINDOW_MS
        if (pulseRunning) return
        if (lastWakeAt.get() != 0L && now - lastWakeAt.get() in 0 until REVIVE_WINDOW_MS) return
        lastWakeAt.set(now)
        startPulseSeries(reason)
    }

    private fun startPulseSeries(reason: String) {
        pulseRunning = true
        val generation = ++pulseGeneration
        Log.i(TAG, "reviving CarPlay after wake ($reason)")
        LAUNCH_DELAYS_MS.forEachIndexed { index, delay ->
            handler.postDelayed({
                if (generation != pulseGeneration) return@postDelayed
                if (index == LAUNCH_DELAYS_MS.lastIndex) pulseRunning = false
                reviveOnce("$reason pulse ${delay}ms")
            }, delay)
        }
    }

    private fun reviveOnce(reason: String) {
        // Already the foreground app (the launch stuck): do not disturb it.
        if (HostVisibility.get()) return
        if (!AirPlayPersistence.loadAutoStartOnBoot(this)) return

        val protocol = AirPlayPersistence.loadProtocol(this)
        val target = ProtocolRouter.resolveProtocolComponent(this, protocol)
        val launch = Intent().apply {
            component = target
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            startActivity(launch)
            Log.i(TAG, "launch $protocol ($reason)")
        } catch (error: RuntimeException) {
            Log.w(TAG, "could not launch $target after wake", error)
        }
    }

    private companion object {
        const val TAG = "hplay-keeper"

        /**
         * When to (re-)assert CarPlay after a wake, in milliseconds. The stock launcher re-asserts a
         * few seconds after the screen returns, so the first launch can be covered; the later pulses
         * land after it settles. Same idea and roughly the same cadence as [BootReceiver].
         */
        val LAUNCH_DELAYS_MS = longArrayOf(0L, 3_000L, 8_000L, 14_000L, 22_000L, 30_000L)

        /** A wake within this window of the previous one is the same event; ignore it. */
        const val REVIVE_WINDOW_MS = 35_000L

        /**
         * How long after a real wake signal a launcher window change still counts as a wake artifact
         * rather than the user browsing the home screen.
         */
        const val WAKE_ARM_WINDOW_MS = 35_000L

        val lastWakeAt = AtomicLong(0L)
    }
}

/** Process-wide "is the CarPlay host currently the foreground app" flag, read by the keeper. */
internal object HostVisibility {
    private val visible = AtomicBoolean(false)

    fun set(value: Boolean) {
        visible.set(value)
    }

    fun get(): Boolean = visible.get()
}
