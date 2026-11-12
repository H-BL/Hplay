package com.hpplay.lingfans

import android.content.Context
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

/** One `readData` item: never merged with defaults, [status] decides how it may be shown. */
data class LingFansReading(val key: String, val value: Any?, val status: String) {
    /** Only `ok` / `stale` values are real readings; `simulated` is shown with a watermark. */
    val usable: Boolean get() = status == "ok" || status == "stale"
}

/**
 * Thin client for the LingFans co-creation provider (`readData` / `executeControl`).
 *
 * Blocking by design: call it from a worker thread. [LingFansPresence] owns availability; this class
 * only speaks the protocol and never retries a command whose outcome is unknown.
 */
object LingFansClient {

    /** Snapshot of the given keys, keyed by item id. Empty when the desktop is not reachable. */
    fun read(context: Context, keys: List<String>): Map<String, LingFansReading> {
        val authority = readyAuthority(context) ?: return emptyMap()
        val uri = Uri.parse("content://$authority")
        val input = Bundle().apply { putStringArray("keys", keys.toTypedArray()) }
        val reply = runCatching { context.contentResolver.call(uri, "readData", null, input) }
            .getOrNull() ?: return emptyMap()
        if (!reply.getBoolean("ok", false)) return emptyMap()

        val payload = reply.getString("payloadJson")
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        val items = payload.optJSONArray("items") ?: return emptyMap()

        val readings = LinkedHashMap<String, LingFansReading>(items.length())
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val key = item.optString("id")
            if (key.isEmpty()) continue
            val value = if (item.isNull("value")) null else item.opt("value")
            readings[key] = LingFansReading(key, value, item.optString("status", "no_data"))
        }
        return readings
    }

    /**
     * Sends a control and returns the `commandId`.
     *
     * `SENT` (queried later through `getControlResult`) only means the vehicle link accepted the
     * request; the caller must never report it as "done".
     */
    fun execute(context: Context, capabilityId: String, paramsJson: String): String? {
        val authority = readyAuthority(context) ?: return null
        val uri = Uri.parse("content://$authority")
        val input = Bundle().apply { putString("paramsJson", paramsJson) }
        val reply = runCatching { context.contentResolver.call(uri, "executeControl", capabilityId, input) }
            .getOrNull() ?: return null
        if (!reply.getBoolean("ok", false)) return null
        val payload = reply.getString("payloadJson")
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return payload.optString("commandId").ifEmpty { null }
    }

    /**
     * The authority of a reachable desktop.
     *
     * [LingFansPresence.cached] expires after 60s; a long-running rail must not go permanently blank
     * because nobody re-probed, so a stale cache triggers one probe instead of giving up.
     */
    private fun readyAuthority(context: Context): String? {
        val cached = LingFansPresence.cached() as? LingFansPresence.State.Ready
        if (cached != null) return cached.authority
        val probed = LingFansPresence.probe(context, useCache = false) as? LingFansPresence.State.Ready
        return probed?.authority
    }
}
