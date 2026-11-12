package com.hpplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Looper
import android.util.Log
import com.hpplay.NANOS_PER_MILLISECOND
import com.hpplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Attaches CarPlay to the Wi-Fi network the head unit is *already* joined to.
 *
 * This is the "same LAN" mode: the car and the iPhone share one external access point (a home/office
 * router, a phone hotspot, ...). Nothing is created here and nothing is asked of the user — the
 * manager simply reads whatever Wi-Fi the head unit is currently connected to and reports the
 * interface, host address, real SSID, passphrase, band and channel that the iAP2 wireless payload
 * (`0x5703`) must carry so the iPhone can join the *same* network and reach the AirPlay port.
 *
 * Wi-Fi is switched on if it is off (best effort: the app API first, then the head unit's own adb
 * channel via `svc wifi enable`), so a user who only ever turned Wi-Fi off does not have to go into
 * the system settings first. The real passphrase is read from the system Wi-Fi store over the adb
 * channel when it is available; otherwise the (possibly masked) app-visible value is used.
 */
class ExistingWifiManager(
    context: Context,
    /**
     * When true, the manager actively moves the station link onto 5 GHz before reporting the
     * network: it scans for the joined SSID's 5 GHz BSSID and pins/reassociates to it through the
     * head unit's adb channel. Without the channel it degrades to a truthful "still on 2.4 GHz"
     * report instead of pretending the link is 5 GHz.
     */
    private val force5Ghz: Boolean = false,
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")

    @Volatile
    private var closed = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ExistingWifiManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        ensureWifiEnabled()
        if (force5Ghz) ensure5GhzLink()

        val deadlineNanos = deadlineAfter(timeoutMillis)
        var lastReason = "no active Wi-Fi connection was found"
        while (true) {
            check(!closed) { "ExistingWifiManager is closed" }
            val attempt = tryAttach()
            attempt.info?.let { return it }
            lastReason = attempt.reason ?: lastReason

            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for the head unit's Wi-Fi " +
                        "connection: $lastReason",
                )
            }
            sleep(minOf(remainingNanos, ATTACH_POLL_NANOS))
        }
    }

    override fun close() {
        closed = true
    }

    /**
     * Switches Wi-Fi on if it is off, so "same LAN" mode works without a trip to system settings.
     *
     * The public [WifiManager.setWifiEnabled] API is a no-op for a normal app on these ROMs, so the
     * head unit's own adb channel (`svc wifi enable`) is used as the real path when it is present.
     * Both attempts are best effort: a device that refuses simply keeps whatever state it has.
     */
    @Suppress("DEPRECATION")
    private fun ensureWifiEnabled() {
        runCatching {
            if (!wifiManager.isWifiEnabled) wifiManager.isWifiEnabled = true
        }
        runCatching {
            if (RootShell.isAvailable()) {
                RootShell.run(FiveGhzHotspotCommands.setWifiEnabled(true))
            }
        }
    }

    private fun tryAttach(): AttachAttempt {
        val connection = readWifiConnection()
            ?: return AttachAttempt(null, "no active Wi-Fi transport is up")
        if (connection.ssid == null || connection.ssid == UNKNOWN_SSID) {
            // Android reports "<unknown ssid>" when the app lacks location/scan access; it must not
            // be treated as a real SSID.
            return AttachAttempt(null, "the connected SSID is not readable yet")
        }

        val localInterface = findWifiInterface(connection.interfaceName)
            ?: return AttachAttempt(null, "the Wi-Fi interface has no usable address yet")
        val hostAddress = localInterface.address
        val frequencyMHz = connection.frequencyMHz
        val channel = frequencyMHz?.let(::wifiFrequencyMhzToChannel) ?: 0
        if (channel == 0) {
            Log.w(
                TAG,
                "Could not derive a channel for the existing Wi-Fi network; reporting iAP2 " +
                    "channel 0 (auto)",
            )
        }
        return AttachAttempt(
            info = WirelessHotspotInfo(
                ssid = connection.ssid,
                passphrase = resolvePassphrase(connection.ssid),
                security = connection.security,
                channel = channel,
                frequencyMHz = frequencyMHz,
                bssid = connection.bssid,
                interfaceName = localInterface.name,
                hostAddress = hostAddress,
                bandLabel = frequencyMHz?.let(::bandLabel) ?: "Auto",
                backend = WirelessHotspotBackend.EXISTING_WIFI,
                channelSource = ChannelSource.FRAMEWORK.label,
            ),
            reason = null,
        )
    }

    /**
     * The real passphrase of the joined network, so the iPhone can be told to join the same AP.
     *
     * A normal app only ever sees `"*"` for [WifiManager]'s pre-shared key, so the root/adb channel
     * is asked for the system Wi-Fi store first. Anything that is masked or empty is dropped rather
     * than handed to the phone (a `"*"` passphrase makes the iPhone fail to join and drop the link).
     */
    private fun resolvePassphrase(ssid: String): String {
        runCatching {
            if (RootShell.isAvailable()) {
                val xml = RootShell.run(FiveGhzHotspotCommands.readWifiConfigStore())
                parsePsk(xml, ssid)?.let { return it }
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            wifiManager.configuredNetworks
                ?.firstOrNull { it.SSID?.trim('"') == ssid }
                ?.preSharedKey?.trim('"')
                ?.takeIf { it.isNotEmpty() && it != "*" }
                ?.let { return it }
        }
        Log.w(TAG, "Existing Wi-Fi passphrase not readable for '$ssid'; sending an empty one")
        return ""
    }

    /** Pulls the PSK for [ssid] out of the system `WifiConfigStore.xml` body. */
    private fun parsePsk(xml: String, ssid: String): String? =
        xml.split("<Network>")
            .firstOrNull { it.contains("&quot;$ssid&quot;") }
            ?.let {
                Regex("<string name=\"PreSharedKey\">&quot;(.*?)&quot;</string>")
                    .find(it)?.groupValues?.getOrNull(1)
            }
            ?.takeIf { it.isNotEmpty() && it != "*" }

    // --- 5 GHz station switch -----------------------------------------------------------------

    /**
     * Moves the station link onto the joined SSID's 5 GHz radio.
     *
     * Best effort and honest: it does nothing when the link is already 5 GHz or when the adb channel
     * is missing, and if the reassociation never lands on a 5 GHz frequency it simply returns so the
     * caller reports the real band (the status pill then shows 2.4 GHz rather than a fake 5 GHz).
     */
    private fun ensure5GhzLink() {
        val current = currentFrequencyMhz()
        if (current != null && WifiBandParser.is5Ghz(current)) {
            Log.i(TAG, "Same-LAN: already on 5 GHz ($current MHz)")
            return
        }
        if (!RootShell.isAvailable()) {
            Log.w(TAG, "Same-LAN: 5 GHz requested but no adb channel; leaving the link as-is")
            return
        }
        val ssid = joinedSsid()
        if (ssid.isNullOrBlank()) {
            Log.w(TAG, "Same-LAN: 5 GHz requested but the joined SSID is unknown")
            return
        }
        repeat(2) { attempt ->
            if (pinTo5GhzBssid(ssid) && awaitFrequency5Ghz()) {
                Log.i(TAG, "Same-LAN: reassociated to 5 GHz for '$ssid'")
                return
            }
            Log.w(TAG, "Same-LAN: 5 GHz attempt ${attempt + 1} did not land on 5 GHz")
        }
    }

    /** Scans, finds the SSID's strongest 5 GHz BSSID and pins the saved network to it. */
    private fun pinTo5GhzBssid(ssid: String): Boolean {
        val aps = scanAps()
        val target = aps
            .filter { it.ssid == ssid && WifiBandParser.is5Ghz(it.frequencyMhz) }
            .maxByOrNull { it.frequencyMhz }
            ?: run {
                Log.w(TAG, "Same-LAN: no 5 GHz BSSID seen for '$ssid' in scan")
                return false
            }
        val ids = WifiBandParser.parseWpaListNetworks(shell(WifiBandCommands.wpaListNetworks()))
        val id = ids[ssid] ?: run {
            Log.w(TAG, "Same-LAN: '$ssid' is not a saved network; cannot pin its BSSID")
            return false
        }
        shell(WifiBandCommands.wpaSetNetworkBssid(id, target.bssid))
        shell(WifiBandCommands.wpaSelectNetwork(id))
        shell(WifiBandCommands.wpaEnableNetwork(id))
        shell(WifiBandCommands.wpaReassociate())
        Log.i(TAG, "Same-LAN: pinned '$ssid' to 5 GHz BSSID ${target.bssid} (${target.frequencyMhz} MHz)")
        return true
    }

    /** Scans for access points, preferring `cmd wifi` and falling back to `iw`. */
    private fun scanAps(): List<WifiBandParser.Ap> {
        shell(WifiBandCommands.startScan())
        Thread.sleep(SCAN_SETTLE_MILLIS)
        val viaCmd = runCatching { WifiBandParser.parseCmdScan(shell(WifiBandCommands.listScanResults())) }
            .getOrDefault(emptyList())
        if (viaCmd.isNotEmpty()) return viaCmd
        return runCatching { WifiBandParser.parseIwScan(shell(WifiBandCommands.iwScan())) }
            .getOrDefault(emptyList())
    }

    /** Polls the connection until it reports a 5 GHz frequency, or the timeout elapses. */
    private fun awaitFrequency5Ghz(): Boolean {
        val deadline = System.currentTimeMillis() + REASSOC_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val freq = currentFrequencyMhz()
            if (freq != null && WifiBandParser.is5Ghz(freq)) return true
            Thread.sleep(REASSOC_POLL_MILLIS)
        }
        return false
    }

    private fun joinedSsid(): String? = try {
        unquote(wifiManager.connectionInfo?.ssid)?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    } catch (_: SecurityException) {
        null
    }

    private fun currentFrequencyMhz(): Int? = runCatching {
        WifiBandParser.currentFrequencyMhz(shell(WifiBandCommands.wpaStatus()))
    }.getOrNull() ?: try {
        wifiManager.connectionInfo?.frequency?.takeIf { it > 0 }
    } catch (_: SecurityException) {
        null
    }

    private fun shell(command: String): String = runCatching { RootShell.run(command) }.getOrDefault("")

    private fun readWifiConnection(): WifiConnection? {
        val manager = connectivityManager ?: return null
        val wifiNetworks = manager.allNetworks.filter { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@filter false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val network = wifiNetworks.singleOrNull() ?: run {
            if (wifiNetworks.size > 1) {
                Log.i(TAG, "Existing Wi-Fi: ${wifiNetworks.size} Wi-Fi networks are up; waiting")
            }
            return null
        }
        val linkProperties = manager.getLinkProperties(network) ?: return null
        val wifiInfo = currentWifiInfo() ?: return null
        val bssid = wifiInfo.bssid?.lowercase()?.takeIf { BSSID.matches(it) }
        val frequencyMHz = wifiInfo.frequency.takeIf { it > 0 }
        return WifiConnection(
            ssid = unquote(wifiInfo.ssid),
            bssid = bssid,
            interfaceName = linkProperties.interfaceName,
            frequencyMHz = frequencyMHz,
            security = securityFor(bssid, unquote(wifiInfo.ssid)),
        )
    }

    @SuppressLint("MissingPermission")
    private fun currentWifiInfo() = try {
        wifiManager.connectionInfo
    } catch (_: SecurityException) {
        null
    }

    /**
     * Security of the connected network, read from the matching scan result. The head unit's own
     * connection info does not expose it on every ROM, and an unknown value must not be advertised
     * as "open": the reference app sends WPA/WPA2 as the safe default, and so do we.
     */
    private fun securityFor(bssid: String?, ssid: String?): Iap2WirelessSecurity {
        val results = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            null
        } ?: return DEFAULT_SECURITY
        val match = results.firstOrNull { result ->
            (bssid == null || result.BSSID.equals(bssid, ignoreCase = true)) &&
                (ssid == null || result.SSID == ssid)
        } ?: return DEFAULT_SECURITY
        return mapScanSecurity(match.capabilities)
    }

    private fun mapScanSecurity(capabilities: String?): Iap2WirelessSecurity {
        val value = capabilities ?: return DEFAULT_SECURITY
        return when {
            value.contains("SAE") && value.contains("PSK") -> Iap2WirelessSecurity.WPA3_TRANSITION
            value.contains("SAE") -> Iap2WirelessSecurity.WPA3_ONLY
            value.contains("WPA2") || value.contains("WPA") -> Iap2WirelessSecurity.WPA_WPA2
            value.contains("WEP") -> Iap2WirelessSecurity.WEP
            value.contains("ESS") -> Iap2WirelessSecurity.NONE
            else -> DEFAULT_SECURITY
        }
    }

    private fun findWifiInterface(expectedName: String?): WifiInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        return Collections.list(interfaces)
            .asSequence()
            .filter { networkInterface ->
                (expectedName == null || networkInterface.name == expectedName) &&
                    networkInterface.name.startsWith("wlan")
            }
            .mapNotNull { networkInterface ->
                networkInterface.usableAddress()?.let { address ->
                    WifiInterface(name = networkInterface.name, address = address)
                }
            }
            .firstOrNull()
    }

    private fun NetworkInterface.usableAddress(): InetAddress? {
        var ipv6: Inet6Address? = null
        for (address in Collections.list(inetAddresses)) {
            if (address is Inet4Address && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress
            ) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && ipv6 == null) {
                ipv6 = if (address.scopeId == index) {
                    address
                } else {
                    try {
                        Inet6Address.getByAddress(null, address.address, this)
                    } catch (_: UnknownHostException) {
                        null
                    }
                }
            }
        }
        return ipv6
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun sleep(nanos: Long) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while attaching to the existing Wi-Fi", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private class WifiConnection(
        val ssid: String?,
        val bssid: String?,
        val interfaceName: String?,
        val frequencyMHz: Int?,
        val security: Iap2WirelessSecurity,
    )

    /** One attach attempt: either a usable [info] or the [reason] it could not be built yet. */
    private class AttachAttempt(
        val info: WirelessHotspotInfo?,
        val reason: String?,
    )

    private class WifiInterface(
        val name: String,
        val address: InetAddress,
    )

    private companion object {
        const val TAG = "hplay-usb"
        const val UNKNOWN_SSID = "<unknown ssid>"
        val ATTACH_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(250)
        val BSSID = Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
        val DEFAULT_SECURITY = Iap2WirelessSecurity.WPA_WPA2
        const val SCAN_SETTLE_MILLIS = 3_000L
        const val REASSOC_TIMEOUT_MILLIS = 15_000L
        const val REASSOC_POLL_MILLIS = 1_000L
    }
}
