package com.hpplay.network

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * 读取本机蓝牙 MAC（Android 8+ 普通应用拿不到，`BluetoothAdapter.address` 只会返回
 * 02:00:00:00:00:00），用于 CarLink 广播身份块里的 carId。
 *
 * 这台车机有回环 adb root 通道，直接问系统拿真实地址；拿不到时退回适配器返回的地址。
 */
object LocalBluetoothAddress {
    private const val TAG = "BtAddr"
    private val cache = AtomicReference<String?>(null)

    /** 已解析出的地址（不触发系统查询）；未解析时返回 null。 */
    fun peek(): String? = cache.get()

    /** 12 位小写 hex，例如 "50c3a22608e2"；完全拿不到时返回 null。 */
    fun hex(context: Context, adapter: BluetoothAdapter?): String? {
        cache.get()?.let { return it }
        val fromRoot = readFromSystem()
        val mac = fromRoot ?: adapter?.address?.takeIf { !isPlaceholder(it) }
        val hex = mac?.replace(":", "")?.replace("-", "")?.lowercase()
            ?.takeIf { it.length == 12 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
        if (hex != null) cache.set(hex)
        return hex
    }

    private fun isPlaceholder(mac: String): Boolean {
        val hex = mac.replace(":", "").lowercase()
        return hex == "020000000000" || hex == "000000000000"
    }

    private fun readFromSystem(): String? {
        val dumpsys = runCatching {
            RootShell.run("dumpsys bluetooth_manager")
        }.getOrNull() ?: return null
        // 先找 "Address: 50:C3:A2:26:08:E2" 这种行。
        Regex("(?i)\\bAddress\\s*:\\s*([0-9a-f]{2}(?::[0-9a-f]{2}){5})")
            .find(dumpsys)?.groupValues?.getOrNull(1)?.let { mac ->
                if (!isPlaceholder(mac)) return mac
            }
        // 再退回 bt_config.conf。
        val config = runCatching {
            RootShell.run("cat /data/misc/bluedroid/bt_config.conf 2>/dev/null")
        }.getOrNull()
        if (!config.isNullOrBlank()) {
            Regex("(?im)^\\s*Address\\s*=\\s*([0-9a-f]{2}(?::[0-9a-f]{2}){5})")
                .find(config)?.groupValues?.getOrNull(1)?.let { mac ->
                    if (!isPlaceholder(mac)) return mac
                }
        }
        Log.w(TAG, "local Bluetooth address unavailable")
        return null
    }
}
