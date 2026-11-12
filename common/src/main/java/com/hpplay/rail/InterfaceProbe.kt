package com.hpplay.rail

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.hpplay.lingfans.LingFansPresence

/**
 * One-shot diagnostic that enumerates what the LingFans desktop and 「哨了个兵」 actually expose on
 * this head unit, and logs the result. Debug builds only: it exists to answer "which interface is
 * usable here" on a unit whose desktop build may predate the co-creation contract.
 */
object InterfaceProbe {

    private const val TAG = "iface-probe"
    private const val DESKTOP = "com.zzh.launcher50s.alpha"
    private const val SUPER_LINGFANS = "com.zzh.superlingfans"
    private const val SENTRY = "com.ccffee.shaolegebing"

    fun run(context: Context) {
        Thread({
            runCatching { probeDesktopProviders(context) }
            runCatching { probeAuthorities(context) }
            runCatching { probeCocreate(context) }
            Log.i(TAG, "probe done")
        }, "iface-probe").start()
    }

    /** Reads through the documented API exactly the way the rail does, and logs the raw reply. */
    private fun probeCocreate(context: Context) {
        val presence = LingFansPresence.probe(context, useCache = false)
        Log.i(TAG, "presence=" + presence)
        val authority = (presence as? LingFansPresence.State.Ready)?.authority ?: return
        val uri = Uri.parse("content://" + authority)
        val input = android.os.Bundle().apply {
            putStringArray("keys", arrayOf("battery.soc", "vehicle.gearName", "hvac.temperature"))
        }
        val reply = runCatching { context.contentResolver.call(uri, "readData", null, input) }.getOrNull()
        if (reply == null) {
            Log.i(TAG, "readData -> null")
            return
        }
        Log.i(
            TAG,
            "readData ok=" + reply.getBoolean("ok") +
                " code=" + reply.getString("code") +
                " msg=" + reply.getString("message"),
        )
        // Which HVAC / vehicle signals does the desktop actually confirm right now?
        val probeKeys = arrayOf(
            "hvac.power", "hvac.ac", "hvac.fanSpeed", "hvac.temperature", "hvac.auto",
            "vehicle.speed", "vehicle.gear", "vehicle.gearName",
        )
        val hvac = runCatching {
            context.contentResolver.call(uri, "readData", null, android.os.Bundle().apply {
                putStringArray("keys", probeKeys)
            })
        }.getOrNull()
        val items = runCatching {
            org.json.JSONObject(hvac?.getString("payloadJson") ?: "{}").optJSONArray("items")
        }.getOrNull()
        if (items != null) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                Log.i(
                    TAG,
                    "signal " + item.optString("id") + " = " + item.opt("value") +
                        " [" + item.optString("status") + "] " + item.optString("message"),
                )
            }
        }
        Log.i(TAG, "readData payload=" + reply.getString("payloadJson"))

        // The rail asks for all 17 keys at once; the desktop may reject an unknown id entirely.
        val railKeys = arrayOf(
            "battery.soc", "battery.availableEnergy", "charging.active", "charging.remainingMinutes",
            "vehicle.gearName", "vehicle.speed",
            "body.door.driver", "body.door.passenger", "body.door.rearLeft",
            "body.door.rearRight", "body.door.tailgate",
            "hvac.temperature", "hvac.fanSpeed", "hvac.ac",
            "media.title", "media.artist", "media.playing",
        )
        val wide = runCatching {
            context.contentResolver.call(uri, "readData", null, android.os.Bundle().apply {
                putStringArray("keys", railKeys)
            })
        }.getOrNull()
        Log.i(
            TAG,
            "readData(17 keys) ok=" + wide?.getBoolean("ok") +
                " code=" + wide?.getString("code") +
                " msg=" + wide?.getString("message"),
        )

        val caps = runCatching { context.contentResolver.call(uri, "getCapabilities", null, null) }.getOrNull()
        if (caps != null) {
            val ids = runCatching {
                org.json.JSONObject(caps.getString("payloadJson") ?: "{}")
                    .optJSONArray("capabilities")
            }.getOrNull()
            Log.i(TAG, "capabilities count=" + (ids?.length() ?: -1))
        }
    }

    private fun probeDesktopProviders(context: Context) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val providers = pm.getInstalledPackages(PackageManager.GET_PROVIDERS)
        for (pkg in providers.map { it.packageName }) {
            if (pkg != DESKTOP && pkg != SUPER_LINGFANS && pkg != SENTRY) continue
            @Suppress("DEPRECATION")
            val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS) }.getOrNull() ?: continue
            val list = info.providers.orEmpty()
            Log.i(TAG, "== $pkg providers: ${list.size}")
            for (provider in list) {
                Log.i(TAG, "   ${provider.name} authority=${provider.authority} exported=${provider.exported}")
            }
        }
    }

    private fun probeAuthorities(context: Context) {
        val candidates = listOf(
            "com.zzh.launcher50s.alpha.link",
            "com.zzh.launcher50s.alpha.door",
            "com.zzh.launcher50s.alpha.power",
            "com.zzh.superlingfans.status",
            "com.ccffee.shaolegebing.public_state",
            "com.ccffee.shaolegebing.capture_state",
        )
        for (authority in candidates) {
            for (path in listOf("", "/state", "/status", "/recording", "/snapshot")) {
                query(context, "content://$authority$path")
            }
        }
    }

    private fun query(context: Context, url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val cursor: Cursor? = runCatching { context.contentResolver.query(uri, null, null, null, null) }
            .getOrNull()
        if (cursor == null) {
            Log.i(TAG, "$url -> null (no provider or denied)")
            return
        }
        cursor.use {
            val cols = it.columnNames.joinToString(",")
            Log.i(TAG, "$url -> rows=${it.count} columns=[$cols]")
            if (it.moveToFirst()) {
                val row = it.columnNames.joinToString(", ") { name ->
                    "$name=${runCatching { it.getString(it.getColumnIndexOrThrow(name)) }.getOrNull()}"
                }
                Log.i(TAG, "   first row: $row")
            }
        }
    }
}
