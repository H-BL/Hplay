package com.hpplay.network

/**
 * The shell vocabulary the "force 5 GHz hotspot" flow speaks over the head unit's adb channel.
 *
 * Every string here is a command the flow sends through [RootShell]; they are pulled out of
 * [ForceFiveGhzHotspotManager] and kept free of Android types so the exact bytes can be asserted in
 * a plain unit test. That matters because a typo in one of them is invisible until the moment the
 * feature runs on a car, where the failure mode is "the hotspot silently never comes up".
 *
 * How 5 GHz is actually selected on this ROM:
 *  - The on-disk `hostapd_ap0.conf` is a **vendor-HAL product**, not an input — hostapd is started
 *    without a config file and receives its parameters over hwbinder, so rewriting that file has no
 *    effect. The band that matters is the framework SoftAp configuration's `apBand`, which is only
 *    reachable through the binder transaction `IWifiManager.setWifiApConfiguration` (49).
 *  - Transaction 49 rejects a root shell (uid 0) with `SecurityException` and a normal app is denied
 *    too, so the flow runs a tiny `app_process` helper under **uid 1000 (system)**, the identity the
 *    Wi-Fi service accepts. [deployHelper]/[runHelper] stage and run that helper.
 *  - The country code is still set first (`service call wifi 27 s16 US`): it is what makes the
 *    driver treat 5 GHz as available.
 */
object FiveGhzHotspotCommands {
    /** Country code with the widest 5 GHz channel set on consumer hardware. */
    const val COUNTRY_CODE_UNITED_STATES = "US"

    /** SoftAp band value the framework understands: 0 = 2.4 GHz, 1 = 5 GHz. */
    const val BAND_2GHZ = 0
    const val BAND_5GHZ = 1

    /** Binder transaction that sets the Wi-Fi country code; `s16` marks the string16 argument. */
    fun setCountryCode(country: String = COUNTRY_CODE_UNITED_STATES): String =
        "service call wifi 27 s16 $country"

    /** Binder transaction that reads the Wi-Fi country code back, used to confirm the injection. */
    fun readCountryCode(): String = "service call wifi 28"

    /** Confirms the adb shell is actually running as root before any state is touched. */
    fun probeRoot(): String = "id"

    /** Lists candidate hostapd config paths so the caller can pick whichever exists. */
    fun listHostapdConfigs(paths: List<String>): String = "ls ${paths.joinToString(" ")}"

    /** Reads a hostapd config body verbatim. */
    fun readConfig(path: String): String = "cat '$path'"

    // --- the app_process helper that drives IWifiManager 48/49 -----------------------------------

    /** Where the helper dex is staged on the head unit. */
    const val HELPER_PATH = "/data/local/tmp/hotspot_ap.dex"

    /** `app_process` entry point; the device's own binary, so no payload is pushed for it. */
    private const val APP_PROCESS = "/system/bin/app_process"

    /** `runconuid` is the ROM's tool for dropping to a fixed uid/gid; present on this head unit. */
    private const val RUN_CON_UID = "/system/xbin/runconuid"

    /** uid/gid of the `system` user — the only identity `setWifiApConfiguration` accepts. */
    private const val SYSTEM_UID = 1000

    /**
     * Stages the helper on the head unit.
     *
     * `base64 -d > file` fed over adb stdin decodes the payload verbatim (the content is base64, so
     * no shell metacharacter can corrupt it) and needs no binary-safe transfer beyond a heredoc.
     */
    fun deployHelper(base64: String): String =
        "base64 -d > '$HELPER_PATH' <<'XCP'\n$base64\nXCP\nchmod 644 '$HELPER_PATH'"

    /** Reads the framework SoftAp band back: prints `HOTSPOTAP:GET band=<0|1> …`. */
    fun readApConfig(callerPackage: String): String =
        runHelper("get '$callerPackage'")

    /** Sets the framework SoftAp band and reads it back: prints `HOTSPOTAP:SET ok=<bool> …`. */
    fun writeApConfig(band: Int, callerPackage: String): String =
        runHelper("set $band '$callerPackage'")

    /**
     * Sets the framework SoftAp band **and** channel together and reads them back:
     * prints `HOTSPOTAP:SET ok=<bool> …` then `HOTSPOTAP:AFTER band=.. channel=.. …`.
     *
     * The band and the channel live in the same framework SoftAp configuration; writing them in a
     * single `setWifiApConfiguration` transaction is what lets the user lock a specific 5 GHz channel
     * (e.g. 149) instead of letting the radio pick one.
     */
    fun writeApBandChannel(band: Int, channel: Int, callerPackage: String): String =
        runHelper("setbc $band $channel '$callerPackage'")

    /**
     * Sets the framework SoftAp SSID and reads it back: prints `HOTSPOTAP:SETSSID ok=<bool> …`.
     *
     * The SSID is what the iPhone scans for, and this ROM keeps it in the framework SoftAp
     * configuration, not in the on-disk hostapd product. A non-ASCII name would be written as a
     * UTF-8 SSID and then advertised as `ssid2=<hex>`, which many iPhones will not match, so the
     * caller passes an ASCII name here.
     */
    fun writeApSsid(ssid: String, callerPackage: String): String =
        runHelper("ssid '$callerPackage' '$ssid'")

    /**
     * Runs the staged helper under the system uid via `app_process`.
     *
     * Two details matter and are easy to get wrong:
     *  - The classpath rides `-Djava.class.path=`, **not** the `CLASSPATH` env var. On a non-PTY adb
     *    shell (what the in-app client opens) `app_process` ignores `CLASSPATH`, falls back to `.`,
     *    and aborts with `SIGABRT` and no output.
     *  - The output is piped through `cat`. `runconuid`'s re-exec'd child loses its output when its
     *    stdout is adbd's raw socket (the in-app client's non-PTY `shell:`), but a pipe makes it
     *    arrive reliably; the same command works either way from an interactive `adb shell`.
     */
    private fun runHelper(arguments: String): String =
        "$RUN_CON_UID -u $SYSTEM_UID -g $SYSTEM_UID $APP_PROCESS " +
            "-Djava.class.path='$HELPER_PATH' /system/bin HotspotAp $arguments 2>&1 | cat"

    // --- tethering and radio ---------------------------------------------------------------------

    /**
     * Grants the app the `WRITE_SETTINGS` app-op.
     *
     * On this head unit `config_mobile_hotspot_provision_app` is empty, so
     * `ConnectivityManager.enforceTetherChangePermission` falls back to the `WRITE_SETTINGS` app-op
     * instead of the signature-level `TETHER_PRIVILEGED` permission. The tethering binder call
     * carries the *app's* identity, not the shell's, so the app-op has to be granted first.
     */
    fun grantTetheringAppOp(packageName: String): String =
        "appops set $packageName WRITE_SETTINGS allow"

    /** Enables or disables the station (client) interface via the `svc` wrapper. */
    fun setWifiEnabled(enabled: Boolean): String = if (enabled) "svc wifi enable" else "svc wifi disable"

    /**
     * Dumps the system Wi-Fi store so the real passphrase of the joined network can be read.
     *
     * A normal app only ever sees `"*"` for the pre-shared key, which would make the iPhone fail to
     * join the same LAN. The store is root-only, so this rides the same adb channel as the rest of
     * the flow; [ExistingWifiManager] parses the SSID's `<Network>` block out of the body.
     */
    fun readWifiConfigStore(): String = "cat /data/misc/wifi/WifiConfigStore.xml"

    /** Reads the link state of the SoftAP interface. */
    fun softApLink(interfaceName: String): String = "ip link show $interfaceName"

    /**
     * Reads the SoftAP's real operating frequency from hostapd itself.
     *
     * `hostapd_cli -i ap0 status` reports the band the radio actually came up on (`freq=5240`), which
     * is the only trustworthy source: the on-disk config and the framework's own report can both
     * disagree with the radio. This is what turns a fake "ap0 is UP" success into a real check.
     */
    fun softApStatus(interfaceName: String): String = "hostapd_cli -i $interfaceName status"

    /**
     * Reads the SoftAP interface's own IPv4 address, or nothing when it has none yet.
     *
     * The framework hands the AP a fresh address out of the hotspot pool every time the hotspot is
     * raised, so a phone that joined the previous incarnation keeps talking to an address the head
     * unit no longer owns.
     */
    fun softApAddress(interfaceName: String): String =
        "ip -o -4 addr show $interfaceName | sed -n 's/.*inet \\([0-9.]*\\)\\/.*/\\1/p'"

    /**
     * Pins the SoftAP interface to a fixed address inside the hotspot subnet.
     *
     * Uses `ip addr replace` so it is idempotent and also moves an address the framework already
     * assigned. The DHCP service keeps leasing out of the same subnet, so clients still get an
     * address as before; only the head unit's own address stops changing between bring-ups.
     */
    fun pinSoftApAddress(interfaceName: String, address: String, prefixLength: Int = 24): String =
        "ip addr replace $address/$prefixLength dev $interfaceName"

    /** Reports hostapd's process id; empty output means the SoftAP is not running at all. */
    fun hostapdPid(): String = "pidof hostapd"

    // --- co-resident daemons --------------------------------------------------------------------

    /**
     * Lists a package's APK paths; empty output means it is not installed.
     *
     * Used to skip the Wi-Fi-owner suppression entirely on head units that do not ship that daemon,
     * so the feature is a no-op there instead of shelling out on every enable.
     */
    fun packageInstalled(packageName: String): String = "pm path $packageName"

    /**
     * Stops a package outright.
     *
     * A vendor daemon on some head units forces Wi-Fi back on a few seconds after the SoftAP rises,
     * which tears the hotspot straight back down. Nothing in the band configuration can out-vote it,
     * so the daemon has to be stopped while the forced hotspot is meant to be up.
     */
    fun forceStopPackage(packageName: String): String = "am force-stop $packageName"

    /**
     * Starts a stopped package's boot receiver without bringing up its UI.
     *
     * This is how a suppressed daemon is handed back when the forced hotspot is released: a
     * force-stopped package ignores ordinary broadcasts and will not be started by its services, but
     * an explicit BOOT_COMPLETED to its boot receiver restarts it exactly as a reboot would, and
     * leaves its launcher activity out of the way.
     */
    fun broadcastBootCompleted(component: String): String =
        "am broadcast -a android.intent.action.BOOT_COMPLETED -n $component"
}
