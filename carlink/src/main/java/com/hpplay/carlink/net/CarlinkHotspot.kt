package com.hpplay.carlink.net

import android.content.Context
import android.util.Log
import com.hpplay.network.LocalOnlyHotspotManager
import com.hpplay.network.WirelessHotspotInfo

/**
 * CarLink 车机端的 Wi-Fi AP 承载。
 *
 * CarLink 的数据面走 Wi-Fi：手机必须连上「车机开的热点」才能连到 AUTH 57209 等端口。之前
 * 只有 [com.hpplay.carlink.conn.CarlinkConnection] 被动监听、从不开热点，手机根本进不来。
 *
 * 这里直接复用 CarPlay 已在用的 [LocalOnlyHotspotManager]（框架 LocalOnlyHotspot），拿到
 * 实时 SSID/密码/车机IP/MAC。
 *
 * 单射频约束：这台 MT8666 车机 `#{managed, AP} <= 1`，STA 与 AP 不能共存。ICCOA 热点模式
 * （ServerInfo.type=1002）要求手机去连车机热点，所以车机必须**先断开当前 WiFi 客户端**再开热点，
 * 否则框架 LocalOnlyHotspot 起不来。参考实现（蒲公英车机端）同样如此。开热点期间车机会暂时脱离
 * 原 WiFi，[stop]/启动失败时会自动连回原 WiFi（[restoreSta]）。
 *
 * [startBlocking] 会阻塞等待系统回调，必须在工作线程调用（[LocalOnlyHotspotManager.start] 的约束）。
 */
class CarlinkHotspot(private val context: Context) {

    /** 热点建好后的实时参数，用于经 BLE ServerInfo 下发给手机。 */
    data class Info(
        val ssid: String,
        val passphrase: String,
        val ipAddress: String,
        val mac: String,
        val freqMhz: Int,
        val channel: Int,
    )

    private val tag = "CarlinkHotspot"
    private var manager: com.hpplay.network.WirelessHotspotManager? = null
    @Volatile private var closed = false
    @Volatile private var releasedSta = false

    /**
     * 建热点并等待就绪。成功返回 [Info]，失败返回 null（调用方据此提示"热点未就绪"）。
     * 必须在工作线程调用。
     *
     * [releaseSta]=true 时先断开当前 WiFi 客户端再开热点（单射频车机 STA+AP 互斥，必须这样）。
     * 热点关闭或启动失败时会自动把车机连回原 WiFi。
     */
    fun startBlocking(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS, releaseSta: Boolean = false): Info? {
        val ctx = context.applicationContext
        if (releaseSta) {
            runCatching {
                val wm = ctx.getSystemService(android.net.wifi.WifiManager::class.java)
                wm?.disconnect()
                Log.i(tag, "releaseSta: disconnected current WiFi client to free radio for AP")
            }
            // 给射频一点时间真正离开 STA，否则随后的开热点仍可能因"STA+AP 冲突"失败。
            runCatching { Thread.sleep(1800) }
            releasedSta = true
        }
        // 热点优先走 5 GHz：Android 9 的框架 LocalOnlyHotspot 只会给 2.4 GHz，
        // 这台车机要 5 GHz 只能走「强制 5 GHz」通道（root adb + 框架 SoftAp 配置）。
        // 没有 root adb 通道时退回框架热点（2.4 GHz），保证功能不整体失效。
        // 默认 149（UNII-3）不少手机/地区不支持，手机连不上热点会表现为「输入验证码后连接失败」。
        // 固定用 36（UNII-1，几乎所有 5G 设备都支持）。
        val forced = com.hpplay.network.ForcedFiveGhzHotspotManager(ctx, preferredChannel = 36)
        val prefer24 = com.hpplay.AirPlayPersistence.loadCarlinkBand(ctx) == "2.4g"
        val forcedUsable = !prefer24 && runCatching {
            android.os.Build.VERSION.SDK_INT < 33 && forced.adbPort() > 0
        }.getOrDefault(false)
        val mgr: com.hpplay.network.WirelessHotspotManager = if (forcedUsable) {
            Log.i(tag, "hotspot: using forced 5 GHz flow (root adb available)")
            forced
        } else {
            Log.i(tag, "hotspot: forced 5 GHz unavailable; falling back to framework hotspot")
            LocalOnlyHotspotManager(ctx, prefer5Ghz = false)
        }
        manager = mgr
        return try {
            val info: WirelessHotspotInfo = mgr.start(timeoutMillis)
            val ip = info.hostAddress?.hostAddress ?: DEFAULT_AP_IP
            Log.i(
                tag,
                "hotspot up: ssid='${info.ssid}' ip=$ip iface=${info.interfaceName} " +
                    "band=${info.bandLabel} channel=${info.channel}",
            )
            Info(
                ssid = info.ssid,
                passphrase = info.passphrase,
                ipAddress = ip,
                mac = info.bssid ?: "",
                freqMhz = info.frequencyMHz ?: 0,
                channel = info.channel,
            )
        } catch (t: Throwable) {
            Log.e(tag, "hotspot start failed", t)
            runCatching { mgr.close() }
            manager = null
            // 开了热点却失败：把车机连回原 WiFi，避免白白掉网。
            if (releasedSta) restoreSta()
            null
        }
    }

    /** 把车机连回原来的 WiFi（热点关闭/启动失败后调用，恢复用户网络与调试通道）。 */
    fun restoreSta() {
        runCatching {
            val wm = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            wm?.reconnect()
            Log.i(tag, "restoreSta: reconnecting head unit to saved WiFi")
        }
        releasedSta = false
    }

    fun stop() {
        if (closed) return
        closed = true
        runCatching { manager?.close() }
        manager = null
        Log.i(tag, "hotspot stopped")
        // 热点关闭后恢复车机原 WiFi 连接。
        restoreSta()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        private const val DEFAULT_AP_IP = "192.168.43.1"
    }
}
