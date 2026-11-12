package com.hpplay.rail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Recording state of 「哨了个兵」 (`com.ccffee.shaolegebing`).
 *
 * The app broadcasts [ACTION] on every public state transition; the LingFans desktop itself
 * registers a receiver for exactly this action, which makes it the supported integration channel
 * (its provider is exported but returns no rows to third parties).
 *
 * The extras are not documented and the APK is packed, so [handle] records the raw bundle once per
 * change and only derives [recording] from keys it can recognise. The raw line is what tells us the
 * real key names; the rail keeps working (showing `—`) until a verdict is available.
 */
object SentryState {

    const val ACTION = "com.ccffee.shaolegebing.action.PUBLIC_STATE_CHANGED"
    private const val TAG = "sentry-state"
    private const val AUTHORITY = "content://com.ccffee.shaolegebing.public_state"

    /** Provider method the LingFans desktop itself calls; the contract is shared with it. */
    private const val METHOD_READ_STATE_V1 = "read_state_v1"
    private const val KEY_OK = "result_ok"
    private const val KEY_HEARTBEAT = "last_encoded_heartbeat_ms"
    private const val KEY_USB_HEALTH = "usb_health"

    private const val CACHE_TTL_MS = 1500L
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedRecording: Boolean? = null
    @Volatile var usbHealth: String? = null
        private set

    /** True/false once a broadcast told us; null while unknown. */
    @Volatile
    var recording: Boolean? = null
        private set

    /** Last raw extras, for the log line and diagnostics. */
    @Volatile
    var lastRaw: String = ""
        private set

    /**
     * Polls the provider for the recording state.
     *
     * The desktop's own rule is `result_ok && last_encoded_heartbeat_ms > 0`; the provider is the
     * documented channel (the broadcast only carries the same heartbeat as a hint). Cached briefly
     * so a one-second rail refresh does not cross processes more than needed.
     */
    fun readRecording(context: Context, now: Long = System.currentTimeMillis()): Boolean? {
        if (now - cachedAt <= CACHE_TTL_MS) return cachedRecording
        val bundle = runCatching {
            context.contentResolver.call(Uri.parse(AUTHORITY), METHOD_READ_STATE_V1, null, null)
        }.getOrNull()
        val recording = if (bundle == null || !bundle.getBoolean(KEY_OK, false)) {
            null
        } else {
            bundle.getLong(KEY_HEARTBEAT, 0L) > 0L
        }
        usbHealth = bundle?.getString(KEY_USB_HEALTH)
        cachedRecording = recording
        cachedAt = now
        return recording
    }

    fun handle(intent: Intent) {
        val extras = intent.extras
        if (extras == null) {
            Log.i(TAG, "state broadcast without extras")
            return
        }
        val parts = StringBuilder()
        var verdict: Boolean? = null
        for (key in extras.keySet()) {
            val value = extras.get(key)
            parts.append(key).append('=').append(value).append("; ")
            if (verdict == null) verdict = interpret(key, value)
        }
        lastRaw = parts.toString()
        cachedAt = 0L
        if (verdict != null) recording = verdict
        Log.i(TAG, "recording=${recording} extras=[${lastRaw}]")
    }

    /** Best-effort mapping of an unknown extra to "is recording"; null when it says nothing. */
    private fun interpret(key: String, value: Any?): Boolean? {
        val name = key.lowercase()
        val looksLikeFlag = name.contains("record") || name.contains("captur") || name.contains("sentry")
        return when (value) {
            is Boolean -> if (looksLikeFlag) value else null
            is String -> when {
                !looksLikeFlag && !name.contains("state") && !name.contains("status") -> null
                value.contains("record", ignoreCase = true) -> true
                value.contains("captur", ignoreCase = true) -> true
                value.contains("idle", ignoreCase = true) -> false
                value.contains("stop", ignoreCase = true) -> false
                value.equals("off", ignoreCase = true) -> false
                value.equals("on", ignoreCase = true) -> true
                else -> null
            }
            else -> null
        }
    }
}

/** Dynamic receiver for [SentryState.ACTION]; register with RECEIVER_EXPORTED (another app sends it). */
class SentryStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SentryState.handle(intent)
    }
}
