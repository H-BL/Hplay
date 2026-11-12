package com.hpplay.network

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.hpplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

/**
 * Adapts [ForceFiveGhzHotspotManager] to the [WirelessHotspotManager] contract so the wireless
 * CarPlay bring-up can raise a real 5 GHz SoftAP.
 *
 * On Android 9 the framework's LocalOnlyHotspot is pinned to 2.4 GHz and this head unit's Wi-Fi
 * service never enables 5 GHz by itself, so the ordinary bring-up cannot use it. When the user has
 * turned the forced-5-GHz option on, the bring-up uses this manager instead: it unlocks 5 GHz, drops
 * the STA connection (the radio cannot do both), raises the SoftAP through the system tethering
 * service and then reports the credentials hostapd is actually broadcasting.
 *
 * The hotspot stays owned by the system; [close] only tears down the SoftAP this manager raised.
 */
class ForcedFiveGhzHotspotManager(
    private val context: Context,
    /** Preselected 5 GHz channel to lock the SoftAp to (0 = auto). */
    preferredChannel: Int = 0,
) : WirelessHotspotManager {
    private val delegate = ForceFiveGhzHotspotManager(context.applicationContext, preferredChannel)

    @Volatile
    private var started = false

    @Volatile
    private var closed = false

    /** Probes the head unit's own adb channel; exposed so the UI can report readiness. */
    fun adbPort(): Int = delegate.adbPort()

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ForcedFiveGhzHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        check(!closed) { "ForcedFiveGhzHotspotManager is closed" }

        val snapshot = delegate.forceEnable()
        if (snapshot.stage != ForceFiveGhzHotspotManager.Stage.SUCCEEDED) {
            throw IOException(
                "Could not raise the forced 5 GHz hotspot: " +
                    (snapshot.detail ?: snapshot.stage.name),
            )
        }
        started = true

        val config = snapshot.configText ?: delegate.configText()
            ?: throw IOException("The forced 5 GHz hotspot configuration could not be read back")
        // 权威来源是框架 SoftAp 配置（手机扫到并加入的就是它）。hostapd 的 conf 文件在这台车机上是
        // 厂商 HAL 产物，可能与实际广播的 SSID/密码不一致——曾出现“车机显示 AndroidShare_xxx，手机
        // 扫到的却是 F510C-409096”的情况，手机因此永远连不上。框架配置读不到时才退回文件内容。
        val wifiManager = context.getSystemService(android.net.wifi.WifiManager::class.java)
        // getWifiApConfiguration() 已从公开 SDK 移除，用反射读取框架里的真实 SoftAp 配置。
        val apConfig: android.net.wifi.WifiConfiguration? = runCatching {
            val method = wifiManager?.javaClass?.getMethod("getWifiApConfiguration")
            method?.invoke(wifiManager) as? android.net.wifi.WifiConfiguration
        }.getOrNull()
        val configSsid = apConfig?.SSID?.takeIf { it.isNotBlank() }
        val configPassphrase = apConfig?.preSharedKey
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }
        val ssid = configSsid
            ?: HostapdCredentials.ssid(config)
            ?: throw IOException("The forced 5 GHz hotspot SSID could not be read back")
        val passphrase = configPassphrase ?: HostapdCredentials.passphrase(config).orEmpty()
        val secured = configPassphrase != null || HostapdCredentials.isSecured(config)
        if (secured && passphrase.length !in 8..63) {
            throw IOException("The forced 5 GHz hotspot is secured but its passphrase is unusable")
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var ap = findApInterface()
        while (ap == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(INTERFACE_POLL_MILLIS)
            ap = findApInterface()
        }
        val apInterface = ap
            ?: throw IOException("The forced 5 GHz hotspot interface did not come up")

        // Pin the AP address before handing it to the iPhone. The framework leases a fresh address out
        // of the hotspot pool on every bring-up; a phone that joined an earlier incarnation then keeps
        // talking to an address the head unit no longer owns, which dropped the session each time the
        // stack was rebuilt. Best-effort: a head unit that refuses the command just keeps the leased
        // address, so this can never make the bring-up fail.
        val pinned = pinHotspotAddress(apInterface.name)

        // Read the channel from the radio instead of from the hostapd product on disk. The product is
        // a vendor HAL output: after a refused channel write it still names the channel a previous
        // run asked for (149), and sending that to the iPhone makes it look for a network that is not
        // there. An observation that is not from the radio is deliberately not advertised.
        val observed = LocalChannelProbe.probe(
            interfaceName = apInterface.name,
            configText = config,
            shell = { command -> runCatching { RootShell.run(command) }.getOrNull() },
        )
        val channel = if (observed.trustworthy) observed.channel else 0
        val frequencyMHz = (if (observed.trustworthy) observed.frequencyMhz else null)
            ?: snapshot.frequencyMHz
            ?: wifiChannelToFrequencyMhz(channel, BAND_5GHZ)

        return WirelessHotspotInfo(
            ssid = ssid,
            passphrase = passphrase,
            security = if (secured) Iap2WirelessSecurity.WPA_WPA2 else Iap2WirelessSecurity.NONE,
            channel = channel,
            frequencyMHz = frequencyMHz,
            bssid = apInterface.mac.joinToString(":") { "%02x".format(it) },
            interfaceName = apInterface.name,
            hostAddress = pinned ?: apInterface.ipv4,
            bandLabel = "5 GHz",
            backend = WirelessHotspotBackend.FORCED_5GHZ_HOTSPOT,
            channelSource = observed.source.label,
        )
    }

    /**
     * Moves the AP interface onto [PINNED_HOTSPOT_ADDRESS], returning the address to advertise.
     *
     * The iPhone is handed this address over iAP2 and then connects back to it for the AirPlay
     * session, so it has to survive a stack rebuild. The framework otherwise leases a random
     * `192.168.43.x` on every bring-up; with ~15 rebuilds observed in 16 minutes that alone was
     * enough to break a live session each time.
     *
     * Returns null when the address could not be applied, so the caller falls back to whatever the
     * interface actually has. Never throws: an address that cannot be pinned is a degraded mode, not
     * a failed bring-up.
     */
    private fun pinHotspotAddress(interfaceName: String): Inet4Address? = runCatching {
        if (!RootShell.isAvailable()) return@runCatching null
        RootShell.run(FiveGhzHotspotCommands.pinSoftApAddress(interfaceName, PINNED_HOTSPOT_ADDRESS))
        // Read the address back rather than trusting the command: the interface keeps whatever the
        // framework leased if the replace was refused, and advertising a made-up address would send
        // the iPhone somewhere that does not answer.
        val actual = RootShell.run(FiveGhzHotspotCommands.softApAddress(interfaceName)).trim()
        if (actual == PINNED_HOTSPOT_ADDRESS) {
            InetAddress.getByName(actual) as? Inet4Address
        } else {
            null
        }
    }.getOrNull()

    /**
     * Tears down the SoftAP **and hands the radio back to the station interface**.
     *
     * The forced flow cannot run STA and AP at once, so [start] turned Wi-Fi off through the adb
     * shell (`svc wifi disable`). [ForceFiveGhzHotspotManager.stopTethering] only removes the AP and
     * leaves Wi-Fi off, which on a car whose owner relies on the head unit's own Wi-Fi (or on a
     * wireless debugging channel) looks like the head unit never recovers. `disable()` is the method
     * that also runs `svc wifi enable` and clears the persisted `sta_disabled` flag, so it is the
     * correct closing partner for [start] — not an optional UI action.
     */
    override fun close() {
        closed = true
        if (started) {
            started = false
            // Restores the station interface this flow took down; reverts to plain teardown when
            // the manager reports it never disabled the STA (e.g. start failed before that step).
            delegate.disable()
        } else {
            // Never raised an AP, but a previous run may still have left the STA flag behind.
            delegate.restoreStaIfStale()
        }
        delegate.close()
    }

    /**
     * Finds the SoftAP interface and its IPv4 address. The AP interface is preferred by name; the
     * address is required because the CarPlay session binds to it.
     */
    private fun findApInterface(): ApInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null

        val candidates = Collections.list(interfaces).mapNotNull { networkInterface ->
            val name = networkInterface.name
            if (name == "lo" || name.startsWith("p2p") || name.startsWith("dummy")) {
                return@mapNotNull null
            }
            val ipv4 = try {
                Collections.list(networkInterface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
            } catch (_: SocketException) {
                null
            } ?: return@mapNotNull null
            val mac = try {
                networkInterface.hardwareAddress
            } catch (_: SocketException) {
                null
            } ?: return@mapNotNull null
            ApInterface(name, ipv4, mac)
        }
        return candidates.firstOrNull { it.name == SOFT_AP_INTERFACE }
            ?: candidates.firstOrNull { it.name.startsWith("ap") }
            ?: candidates.firstOrNull { it.ipv4.hostAddress?.startsWith(HOTSPOT_SUBNET_PREFIX) == true }
    }

    private class ApInterface(
        val name: String,
        val ipv4: Inet4Address,
        val mac: ByteArray,
    )

    private companion object {
        const val SOFT_AP_INTERFACE = "ap0"
        const val HOTSPOT_SUBNET_PREFIX = "192.168.43."

        /**
         * The address the AP is pinned to, so it is identical across bring-ups.
         *
         * `.1` is the conventional AP gateway and is never the first address the framework leases,
         * so pinning there does not collide with a client that already holds a low address.
         */
        const val PINNED_HOTSPOT_ADDRESS = "192.168.43.1"

        const val BAND_5GHZ = 2
        const val INTERFACE_POLL_MILLIS = 250L
    }
}
