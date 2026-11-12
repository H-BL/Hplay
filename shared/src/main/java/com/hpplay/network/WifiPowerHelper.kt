package com.hpplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Turns the head unit's Wi-Fi adapter on for the CarLink 「局域网直连」 mode, so the user does not
 * have to open system settings first.
 *
 * 普通应用在 Android 9 上仍可用 `setWifiEnabled(true)`（API 29 起才被限制）；这台车机还有一条
 * 回环 adb root 通道，所以直接调用失败时再退回 `svc wifi enable`，两条路都失败才返回 false。
 */
object WifiPowerHelper {
    private const val TAG = "WifiPower"

    fun ensureWifiEnabled(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return false
        if (manager.isWifiEnabled) return true
        runCatching {
            @Suppress("DEPRECATION")
            manager.isWifiEnabled = true
        }.onFailure { Log.w(TAG, "setWifiEnabled failed: ${it.message}") }
        if (waitForEnabled(manager, 1_500)) return true
        // 回退：车机自带的回环 adb root 通道。
        val ran = runCatching {
            if (RootShell.port() <= 0) return@runCatching false
            RootShell.run("svc wifi enable")
            true
        }.getOrDefault(false)
        if (!ran) return manager.isWifiEnabled
        Log.i(TAG, "Wi-Fi enabled through the root adb channel")
        return waitForEnabled(manager, 3_000)
    }

    private fun waitForEnabled(manager: WifiManager, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (manager.isWifiEnabled) return true
            runCatching { Thread.sleep(150) }
        }
        return manager.isWifiEnabled
    }
}
