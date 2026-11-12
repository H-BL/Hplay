package com.hpplay.network

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.hpplay.orchestration.isHotspot5GhzChannel
import java.util.concurrent.locks.ReentrantLock

/**
 * Drives the F510C "force 5 GHz hotspot" flow.
 *
 * The head unit supports 5 GHz in hardware but never turns it on by itself, and the two levers that
 * were once thought to select the band do not work on this ROM:
 *  - The on-disk `hostapd_ap0.conf` is a **vendor-HAL product**: hostapd is started with no config
 *    file and receives its parameters over hwbinder, so rewriting the file is silently discarded.
 *  - A country code is still needed (`service call wifi 27 s16 US`) so the driver treats 5 GHz as
 *    available, but it does not select the band on its own.
 *
 * The band that actually matters is the framework SoftAp configuration's `apBand`, reachable only
 * through `IWifiManager.setWifiApConfiguration` (49). That transaction refuses a root shell, so the
 * flow runs a tiny `app_process` helper as uid 1000 (see [ApBandHelper] and [FiveGhzHotspotCommands]).
 * Success is then confirmed against the **radio**, not a file: hostapd's reported frequency must be
 * in the 5 GHz range, otherwise the run fails instead of reporting a fake success.
 *
 * The work is split so the car is never left without Wi-Fi at boot:
 *  - [prepare] runs at boot and only makes the radio *able* to use 5 GHz (country code + apBand).
 *  - [forceEnable] runs when the user asks for the hotspot and actually raises the SoftAP.
 *
 * One more actor is out of the app's hands: on some head units a co-resident vendor daemon forces
 * Wi-Fi back on a few seconds after the SoftAP rises, and the framework then tears the hotspot down
 * again. [forceEnable] stops that daemon (when it is present) and [disable]/[stopTethering] start it
 * again, so the hotspot survives and the head unit is not left without Wi-Fi.
 */
class ForceFiveGhzHotspotManager(
    private val context: Context,
    /**
     * Preselected 5 GHz channel to lock the SoftAp to (0 = auto). Written together with the 5 GHz
     * band in one `setWifiApConfiguration` transaction; a channel the device refuses falls back to
     * the band-only write so the hotspot still comes up (just on the radio's chosen channel).
     */
    private val preferredChannel: Int = 0,
) {
    /** What the last command sequence did. */
    enum class Stage { IDLE, UNLOCKING, WRITING_CONFIG, STARTING, SUCCEEDED, FAILED }

    /** What the device currently looks like, independent of the last command. */
    enum class ObservedState { UNKNOWN, READY, PARTIAL, UNAVAILABLE }

    /** A point-in-time view for the settings UI. */
    data class Snapshot(
        val stage: Stage,
        val observedState: ObservedState,
        val detail: String?,
        val rootAvailable: Boolean,
        val unlocked: Boolean,
        /** SoftAp band the framework reports: 0 = 2.4 GHz, 1 = 5 GHz, null = unknown. */
        val apBand: Int?,
        /** Real operating frequency in MHz when the SoftAP is up, read from hostapd. */
        val frequencyMHz: Int? = null,
        /**
         * The live hostapd configuration that is live after the last command, when one was read. The
         * wireless CarPlay bring-up reads the SSID/passphrase back out of it so the iPhone is handed
         * the credentials the SoftAP is actually broadcasting.
         */
        val configText: String? = null,
        /**
         * True when a co-resident Wi-Fi-owner daemon was stopped so the hotspot could stay up. It is
         * started again when the hotspot is released.
         */
        val suppressedWifiOwner: Boolean = false,
        /** Loopback adb port that answered, or -1 when no adb channel is available. */
        val adbPort: Int = -1,
        /** Channel the user asked for (0 = auto). */
        val requestedChannel: Int = 0,
        /**
         * Channel the radio/framework actually reports after the write, when it was read back.
         *
         * A value that differs from [requestedChannel] is the honest answer to "I picked 36, why is
         * it on 149": the write was accepted but not applied, and the phone must not be told 36.
         */
        val observedChannel: Int? = null,
        /** One-line explanation when the channel did not stick, for the settings UI. */
        val channelNote: String? = null,
    ) {
        val adbAvailable: Boolean get() = adbPort > 0
    }

    private val lock = ReentrantLock(true)

    @Volatile
    private var closed = false

    /** Channel the caller asked for in the last run (0 = auto). */
    @Volatile
    private var requestedChannel = 0

    /** Channel the framework/radio reported after the last write, when it could be read back. */
    @Volatile
    private var observedChannel: Int? = null

    /** Human-readable explanation when the requested channel did not stick. */
    @Volatile
    private var channelNote: String? = null

    /**
     * Whether this flow turned the station interface off, so only it is turned back on.
     *
     * Backed by [prefs] rather than a plain field: the restore path (`disable`) runs from a *new*
     * manager instance, so an in-memory flag would be lost and Wi-Fi would stay off. Persisting it
     * makes the restore correct no matter which instance performs it.
     */
    private var disabledStaForStart: Boolean
        get() = prefs.getBoolean(KEY_STA_DISABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_STA_DISABLED, value).apply() }

    /** Set while the co-resident Wi-Fi-owner daemon is stopped, so only we start it again. */
    private var wifiOwnerSuppressed: Boolean
        get() = prefs.getBoolean(KEY_WIFI_OWNER_SUPPRESSED, false)
        set(value) { prefs.edit().putBoolean(KEY_WIFI_OWNER_SUPPRESSED, value).apply() }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Probes the head unit's own adb channel without changing anything on the device.
     *
     * @return the loopback port that answered, or -1 when no channel is available.
     */
    fun adbPort(): Int = RootShell.port()

    fun observe(): Snapshot = lock.withLock {
        val rootAvailable = RootShell.isAvailable()
        val band = if (rootAvailable) apBandHelper.readBand() else null
        val config = if (rootAvailable) readCurrentConfig() else null
        buildSnapshot(
            stage = Stage.IDLE,
            detail = null,
            rootAvailable = rootAvailable,
            unlocked = rootAvailable && readCountryCodeUnlocked(),
            apBand = band,
            frequencyMHz = null,
            config = config,
        )
    }

    /**
     * Boot-time preparation: set the country code and the framework SoftAp band to 5 GHz. Does not
     * touch the running Wi-Fi connection and does not start the hotspot.
     */
    fun prepare(): Snapshot = execute { path, config ->
        if (!unlockFiveGhz()) {
            fail("the Wi-Fi country code could not be verified")
        }
        applyFiveGhzBand()?.let { return@execute fail(it) }
        enforceAsciiSsid()
        success("5 GHz hotspot is ready", HotspotBand.FIVE_GHZ, null, config, path)
    }

    /**
     * User-triggered enable: prepare, grant the tethering app-op, drop any STA connection (the
     * SoftAP cannot share the radio), then raise the SoftAP and confirm the **radio** came up on
     * 5 GHz.
     */
    fun forceEnable(): Snapshot = execute { path, config ->
        if (!unlockFiveGhz()) {
            fail("the Wi-Fi country code could not be verified")
        }
        applyFiveGhzBand()?.let { return@execute fail(it) }
        enforceAsciiSsid()

        // P3: a SoftAP that is already running keeps the band it was raised with — startTethering
        // does not reload hostapd — so it is torn down first to make the new band take effect.
        runCatching { TetheringController(context).stopWifiTethering() }
        awaitSoftApDown()

        // A vendor daemon on some head units forces Wi-Fi back on right after the AP rises, which
        // tears the hotspot down again. Stop it before raising the AP so the radio stays on 5 GHz.
        suppressWifiOwner()

        grantTetheringPermission()
        disabledStaForStart = disableSta()
        val tethering = TetheringController(context)
        var started = false
        val result = tethering.startWifiTethering(
            onStarted = { started = true },
            onFailed = { started = false },
        )
        if (result == TetheringController.StartResult.UNSUPPORTED) {
            rollback(tethering)
            fail("this device does not allow the app to raise the hotspot")
        }
        val frequency = awaitFiveGhzSoftAp()
        if (frequency == null) {
            val observed = readSoftApFrequency()
            val linkUp = isSoftApLinkUp()
            rollback(tethering)
            fail(
                when {
                    observed != null ->
                        "the hotspot came up on ${SoftApRadio.label(observed)}, not 5 GHz"
                    linkUp ->
                        "the hotspot came up but its band could not be read"
                    else -> "the hotspot did not come up"
                },
            )
        }
        // The radio's own report is the last word on the channel: the framework write may have been
        // accepted and still lost to the vendor HAL, and the settings page must not show 36 then.
        val radioChannel = frequency?.let(::wifiFrequencyMhzToChannel)
        if (radioChannel != null) {
            observedChannel = radioChannel
            channelNote = if (requestedChannel > 0 && radioChannel != requestedChannel) {
                "channel $requestedChannel was not applied; the radio came up on $radioChannel"
            } else {
                channelNote
            }
            Log.i(TAG, "hotspot radio channel=$radioChannel requested=$requestedChannel")
        }
        success("5 GHz hotspot is running", HotspotBand.FIVE_GHZ, frequency, config, path)
    }

    /**
     * Turns the station interface back on when a previous run left it disabled and never restored
     * it — the process was killed, the head unit rebooted, or an older build closed the hotspot
     * without calling [disable].
     *
     * [KEY_STA_DISABLED] is persisted precisely so the restore survives across manager instances,
     * but nothing consumed it unless the user pressed the in-app button. That left the Wi-Fi
     * switched off (and every wireless-debugging channel dead) until someone ran `svc wifi enable`
     * by hand. Calling this on the teardown and bring-up paths makes the flag self-healing.
     *
     * No-op when this flow never disabled the STA, or when the client interface is already up.
     *
     * @return true when Wi-Fi was switched back on by this call.
     */
    fun restoreStaIfStale(): Boolean = lock.withLock {
        if (!disabledStaForStart) return@withLock false
        if (!RootShell.isAvailable()) return@withLock false
        val restored = runCatching {
            RootShell.run(FiveGhzHotspotCommands.setWifiEnabled(true))
            true
        }.getOrDefault(false)
        if (restored) {
            disabledStaForStart = false
            Log.i(TAG, "restored the station interface a previous run left disabled")
        }
        restored
    }

    /** Stops the hotspot and restores Wi-Fi (the framework band is left at 5 GHz). */
    fun disable(): Snapshot = lock.withLock {
        val tethering = TetheringController(context)
        runCatching { tethering.stopWifiTethering() }
            .onFailure { Log.w(TAG, "stopTethering failed: ${it.javaClass.simpleName}") }
        if (disabledStaForStart) {
            runCatching { RootShell.run(FiveGhzHotspotCommands.setWifiEnabled(true)) }
            disabledStaForStart = false
        }
        restoreWifiOwner()
        Snapshot(
            stage = Stage.IDLE,
            observedState = ObservedState.UNKNOWN,
            detail = null,
            rootAvailable = RootShell.isAvailable(),
            unlocked = false,
            apBand = null,
            adbPort = RootShell.port(),
        )
    }

    fun close() {
        closed = true
    }

    /**
     * Tears down only the SoftAP this flow raised, leaving Wi-Fi as it is. Used when a wireless
     * CarPlay session ends; [disable] additionally turns Wi-Fi back on.
     */
    fun stopTethering() {
        runCatching { TetheringController(context).stopWifiTethering() }
            .onFailure { Log.w(TAG, "stopTethering failed: ${it.javaClass.simpleName}") }
        // The AP is gone, so hand back any daemon this flow stopped for it — otherwise the head unit
        // would stay without Wi-Fi (the daemon is what brings the station interface back).
        restoreWifiOwner()
    }

    /**
     * Reads the live hostapd configuration body, or null when there is no adb channel or no file.
     * The wireless bring-up uses it to hand the iPhone the credentials the SoftAP is broadcasting.
     */
    fun configText(): String? = lock.withLock { readCurrentConfig() }

    // --- execution plumbing ---------------------------------------------------------------------

    private fun execute(
        body: (path: String?, config: String?) -> Snapshot,
    ): Snapshot {
        check(!closed) { "ForceFiveGhzHotspotManager is closed" }
        lock.withLock {
            val adbPort = RootShell.port()
            val rootAvailable = adbPort > 0 && RootShell.isAvailable()
            if (!rootAvailable) {
                return Snapshot(
                    stage = Stage.FAILED,
                    observedState = ObservedState.UNAVAILABLE,
                    detail = if (adbPort > 0) {
                        "the adb shell did not grant root"
                    } else {
                        "no adb channel on the head unit"
                    },
                    rootAvailable = false,
                    unlocked = false,
                    apBand = null,
                    adbPort = adbPort,
                )
            }
            val path = discoverHostapdPath()
            val config = path?.let { readHostapdConfig(it) }
            return body(path, config)
        }
    }

    /**
     * Selects the 5 GHz band, and — when a valid [preferredChannel] was requested — locks the
     * SoftAp to that exact channel, **then reads the channel back** instead of trusting the write.
     *
     * A channel the ROM refuses used to fall back to a band-only write silently, which left whatever
     * channel was already in the framework configuration in place (typically 149 from an earlier
     * run) — the user picked 36, the hotspot still came up on 149, and nothing recorded it. The
     * retry order below is what makes the request actually win when the ROM merely dislikes the
     * combined write:
     *
     *  1. band + channel in one transaction;
     *  2. if refused: band alone, then band + channel again (some ROMs only accept a channel once
     *     the band is already 5 GHz);
     *  3. read the channel back and record requested-versus-observed for the caller and the log.
     *
     * Returns a non-null failure message only when even the band cannot be selected; a channel that
     * did not stick is reported through [channelNote] so the hotspot still comes up.
     */
    private fun applyFiveGhzBand(): String? {
        val requested = preferredChannel.takeIf(::isHotspot5GhzChannel)
        requestedChannel = requested ?: 0
        observedChannel = null
        channelNote = null
        if (requested != null) {
            var applied = when (val result = apBandHelper.writeBandChannel(HotspotBand.FIVE_GHZ, requested)) {
                is ApBandHelper.Result.Applied -> true
                is ApBandHelper.Result.Refused -> {
                    Log.w(
                        TAG,
                        "hotspot channel $requested could not be locked (${result.detail}); " +
                            "retrying band-only and then the channel again",
                    )
                    false
                }
                is ApBandHelper.Result.Unavailable -> return "the hotspot helper could not be run: " +
                    result.detail
            }
            if (!applied) {
                when (val band = apBandHelper.writeBand(HotspotBand.FIVE_GHZ)) {
                    is ApBandHelper.Result.Applied -> Unit
                    is ApBandHelper.Result.Refused -> return "the 5 GHz band could not be selected: " +
                        band.detail
                    is ApBandHelper.Result.Unavailable ->
                        return "the hotspot helper could not be run: ${band.detail}"
                }
                applied = when (val retry = apBandHelper.writeBandChannel(HotspotBand.FIVE_GHZ, requested)) {
                    is ApBandHelper.Result.Applied -> true
                    is ApBandHelper.Result.Refused -> {
                        Log.w(TAG, "hotspot channel retry for $requested refused: ${retry.detail}")
                        false
                    }
                    is ApBandHelper.Result.Unavailable -> false
                }
            }
            val observed = apBandHelper.readChannel()
            observedChannel = observed
            if (observed != null && observed != requested) {
                channelNote = "channel $requested was not applied; the hotspot is on $observed"
                Log.w(TAG, "hotspot channel did not stick: asked for $requested, device reports $observed")
            } else if (observed == null && !applied) {
                channelNote = "channel $requested was refused; the hotspot uses the radio's channel"
            } else if (observed == null) {
                channelNote = "channel $requested was written; the device did not report one back"
            }
            // The hotspot still comes up: a channel that did not stick is reported through
            // channelNote rather than failing the whole 5 GHz bring-up.
            return null
        }
        return when (val result = apBandHelper.writeBand(HotspotBand.FIVE_GHZ)) {
            is ApBandHelper.Result.Applied -> null
            is ApBandHelper.Result.Refused -> "the 5 GHz band could not be selected: ${result.detail}"
            is ApBandHelper.Result.Unavailable -> "the hotspot helper could not be run: ${result.detail}"
        }
    }

    /**
     * Rewrites a non-ASCII SoftAp SSID to a stable ASCII default.
     *
     * A Chinese SSID is stored as a UTF-8 SSID and advertised as \`ssid2=<hex>\`, which the iPhone
     * will not match against the name it was handed over iAP2 — the hotspot then never appears on
     * the phone. The name is corrected in the framework SoftAp configuration (the same place the
     * band lives) before the AP is raised; failure is logged, not fatal, so the hotspot still runs.
     */
    private fun enforceAsciiSsid() {
        val current = apBandHelper.readSsid()
        if (current != null && HotspotSsid.isAscii(current)) return
        val target = HotspotSsid.defaultName(ssidSeed)
        when (val result = apBandHelper.writeSsid(target)) {
            is ApBandHelper.SsidResult.Applied ->
                Log.i(TAG, "hotspot SSID rewritten to ASCII: '${current}' -> '${result.ssid}'")
            is ApBandHelper.SsidResult.Refused ->
                Log.w(TAG, "hotspot SSID could not be rewritten: ${result.detail}")
            is ApBandHelper.SsidResult.Unavailable ->
                Log.w(TAG, "hotspot SSID helper unavailable: ${result.detail}")
        }
    }

    /**
     * A stable per-device seed for the fallback SSID, persisted so the advertised name does not
     * change on every restart (which would invalidate an already-paired phone's expectation).
     */
    private val ssidSeed: String
        get() = prefs.getString(KEY_SSID_SEED, null)
            ?: java.util.UUID.randomUUID().toString().replace("-", "").take(8).also {
                prefs.edit().putString(KEY_SSID_SEED, it).apply()
            }

    private fun fail(detail: String): Snapshot = Snapshot(
        stage = Stage.FAILED,
        observedState = observedState(),
        detail = detail,
        rootAvailable = true,
        unlocked = readCountryCodeUnlocked(),
        apBand = runCatching { apBandHelper.readBand() }.getOrNull(),
        adbPort = RootShell.port(),
        requestedChannel = requestedChannel,
        observedChannel = observedChannel,
        channelNote = channelNote,
    )

    private fun success(
        detail: String,
        band: Int,
        frequencyMHz: Int?,
        config: String?,
        path: String?,
    ): Snapshot = Snapshot(
        stage = Stage.SUCCEEDED,
        observedState = ObservedState.READY,
        detail = detail,
        rootAvailable = true,
        unlocked = true,
        apBand = band,
        frequencyMHz = frequencyMHz,
        configText = config,
        suppressedWifiOwner = wifiOwnerSuppressed,
        adbPort = RootShell.port(),
        requestedChannel = requestedChannel,
        observedChannel = observedChannel ?: frequencyMHz?.let(::wifiFrequencyMhzToChannel),
        channelNote = channelNote,
    )

    private fun buildSnapshot(
        stage: Stage,
        detail: String?,
        rootAvailable: Boolean,
        unlocked: Boolean,
        apBand: Int?,
        frequencyMHz: Int?,
        config: String?,
    ): Snapshot = Snapshot(
        stage = stage,
        observedState = observedState(apBand, unlocked),
        detail = detail,
        rootAvailable = rootAvailable,
        unlocked = unlocked,
        apBand = apBand,
        frequencyMHz = frequencyMHz,
        configText = config,
        adbPort = RootShell.port(),
        requestedChannel = requestedChannel,
        observedChannel = observedChannel,
        channelNote = channelNote,
    )

    /**
     * What the device looks like right now: READY once the framework band is 5 GHz and the country
     * code is set, PARTIAL when only one of the two is in place, UNAVAILABLE otherwise.
     */
    private fun observedState(apBand: Int? = runCatching { apBandHelper.readBand() }.getOrNull(),
                              unlocked: Boolean = readCountryCodeUnlocked()): ObservedState = when {
        HotspotBand.isFiveGhz(apBand) && unlocked -> ObservedState.READY
        unlocked || apBand != null -> ObservedState.PARTIAL
        else -> ObservedState.UNAVAILABLE
    }

    // --- country code ---------------------------------------------------------------------------

    private fun unlockFiveGhz(): Boolean {
        if (readCountryCodeUnlocked()) return true
        // Transaction 27 is IWifiManager.setCountryCode(String); s16 marks the string16 argument.
        runCatching { RootShell.run(FiveGhzHotspotCommands.setCountryCode()) }
            .onFailure { Log.w(TAG, "country code injection failed: ${it.javaClass.simpleName}") }
        return waitForCountryCode()
    }

    private fun waitForCountryCode(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        var step = 0
        while (true) {
            val raw = runCatching { RootShell.run(FiveGhzHotspotCommands.readCountryCode()) }.getOrNull()
            if (CountryCodeResponse.isExpected(raw)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(WAIT_STEPS_MILLIS[minOf(step, WAIT_STEPS_MILLIS.size - 1)])
            step++
        }
    }

    private fun readCountryCodeUnlocked(): Boolean = runCatching {
        CountryCodeResponse.isExpected(RootShell.run(FiveGhzHotspotCommands.readCountryCode()))
    }.getOrDefault(false)

    // --- framework SoftAp band ------------------------------------------------------------------

    /** One helper per manager, so the dex is staged at most once per instance. */
    private val apBandHelper: ApBandHelper by lazy {
        ApBandHelper(
            callerPackage = context.packageName,
            assetBase64 = ::loadHelperBase64,
        )
    }

    /** Reads the helper dex from the module's assets; null when it is missing. */
    private fun loadHelperBase64(): String? = runCatching {
        context.assets.open(HELPER_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    // --- hostapd config -------------------------------------------------------------------------

    private fun discoverHostapdPath(): String? {
        val listing = runCatching { RootShell.run(FiveGhzHotspotCommands.listHostapdConfigs(HOSTAPD_PATHS)) }
            .getOrDefault("")
        val present = listing.lineSequence().map { it.trim() }.toSet()
        return HOSTAPD_PATHS.firstOrNull { it in present }
    }

    private fun readHostapdConfig(path: String): String? = runCatching {
        val raw = RootShell.run(FiveGhzHotspotCommands.readConfig(path))
        HostapdConfig.extractConfigText(raw).ifEmpty { null }
    }.getOrNull()

    private fun readCurrentConfig(): String? =
        discoverHostapdPath()?.let { readHostapdConfig(it) }

    // --- tethering ------------------------------------------------------------------------------

    /**
     * Grants this app the tethering app-op through the adb shell.
     *
     * On this head unit `config_mobile_hotspot_provision_app` is empty, so the framework's
     * `ConnectivityManager.enforceTetherChangePermission` falls back to the `WRITE_SETTINGS` app-op
     * instead of the signature-level `TETHER_PRIVILEGED` permission. A normal app starts out with
     * that app-op `default`, so `startTethering` would throw `SecurityException` even from a root
     * shell — the binder call carries the *app's* identity, not the shell's. Granting the app-op
     * first is therefore what makes the tethering call succeed. It is best-effort and never throws:
     * devices that grant the permission some other way simply ignore it.
     */
    private fun grantTetheringPermission() {
        val packageName = context.packageName
        runCatching {
            RootShell.run(FiveGhzHotspotCommands.grantTetheringAppOp(packageName))
        }.onFailure { Log.w(TAG, "granting WRITE_SETTINGS app-op failed: ${it.javaClass.simpleName}") }
    }

    private fun disableSta(): Boolean = runCatching {
        RootShell.run(FiveGhzHotspotCommands.setWifiEnabled(false))
        true
    }.getOrDefault(false)

    /**
     * Stops the co-resident daemon that would otherwise force Wi-Fi back on and kill the hotspot.
     *
     * Some head units run a vendor app that turns the station interface back on within seconds of
     * the SoftAP coming up — the 24-model Starlight S calls `WifiManager.setWifiEnabled(true)` right
     * after the AP rises, and the framework then tears `ap0` down. No band configuration can out-vote
     * that, so the daemon is stopped for as long as the forced hotspot is meant to be up and started
     * again in [restoreWifiOwner]. The package is absent on other head units, so this is a no-op
     * there and the feature behaves exactly as before.
     */
    private fun suppressWifiOwner() {
        val installed = runCatching {
            RootShell.run(FiveGhzHotspotCommands.packageInstalled(WIFI_OWNER_PACKAGE)).contains("package:")
        }.getOrDefault(false)
        if (!installed) return
        runCatching { RootShell.run(FiveGhzHotspotCommands.forceStopPackage(WIFI_OWNER_PACKAGE)) }
            .onFailure { Log.w(TAG, "stopping $WIFI_OWNER_PACKAGE failed: ${it.javaClass.simpleName}") }
        wifiOwnerSuppressed = true
    }

    /** Starts a suppressed Wi-Fi-owner daemon again; a no-op when this flow did not stop one. */
    private fun restoreWifiOwner() {
        if (!wifiOwnerSuppressed) return
        wifiOwnerSuppressed = false
        runCatching {
            RootShell.run(FiveGhzHotspotCommands.broadcastBootCompleted(WIFI_OWNER_RECEIVER))
        }.onFailure { Log.w(TAG, "restarting $WIFI_OWNER_PACKAGE failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Waits for the SoftAP to come up **on 5 GHz**, returning its real frequency in MHz.
     *
     * This is the check that replaces the old "is ap0 UP" test, which passed on 2.4 GHz too and made
     * the flow report success while the radio was still on 2.4 GHz. The frequency is read from
     * hostapd, so it reflects the radio, not the config file or the framework's intent.
     */
    private fun awaitFiveGhzSoftAp(): Int? {
        val deadline = SystemClock.elapsedRealtime() + SOFT_AP_TIMEOUT_MILLIS
        while (true) {
            val frequency = readSoftApFrequency()
            if (frequency != null && SoftApRadio.isFiveGhz(frequency)) return frequency
            if (SystemClock.elapsedRealtime() >= deadline) return null
            Thread.sleep(SOFT_AP_POLL_MILLIS)
        }
    }

    /** Waits for a previously-running SoftAP to go away so the new band can take effect. */
    private fun awaitSoftApDown() {
        val deadline = SystemClock.elapsedRealtime() + SOFT_AP_DOWN_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isSoftApLinkUp()) return
            Thread.sleep(SOFT_AP_POLL_MILLIS)
        }
    }

    /**
     * The SoftAP's real frequency in MHz, or null when it is not up. Read from hostapd's own status
     * (`hostapd_cli -i ap0 status`), which reports the band the radio actually came up on — not the
     * config file or the framework's intent.
     */
    private fun readSoftApFrequency(): Int? {
        val raw = runCatching { RootShell.run(FiveGhzHotspotCommands.softApStatus(SOFT_AP_INTERFACE)) }
            .getOrNull()
        return SoftApRadio.frequencyMHz(raw)
    }

    /** True when the SoftAP interface exists and is administratively up. */
    private fun isSoftApLinkUp(): Boolean = runCatching {
        SoftApRadio.isLinkUp(RootShell.run(FiveGhzHotspotCommands.softApLink(SOFT_AP_INTERFACE)))
    }.getOrDefault(false)

    private fun rollback(tethering: TetheringController) {
        runCatching { tethering.stopWifiTethering() }
        if (disabledStaForStart) {
            runCatching { RootShell.run(FiveGhzHotspotCommands.setWifiEnabled(true)) }
            disabledStaForStart = false
        }
        restoreWifiOwner()
    }

    private companion object {
        const val TAG = "hplay-usb"
        const val SOFT_AP_INTERFACE = "ap0"

        /** The helper dex (base64) shipped in the module's assets. */
        const val HELPER_ASSET = "hotspot/hotspot_ap.dex.b64"

        /** Where the cross-instance restore state lives. */
        const val PREFS_NAME = "hplay_force_5ghz"

        /** Set while this flow has the station interface off. */
        const val KEY_STA_DISABLED = "sta_disabled"

        /** Set while this flow has the Wi-Fi-owner daemon stopped. */
        const val KEY_WIFI_OWNER_SUPPRESSED = "wifi_owner_suppressed"
        const val KEY_SSID_SEED = "ssid_seed"

        /** Vendor daemon that forces Wi-Fi back on (present on the Starlight S head unit only). */
        const val WIFI_OWNER_PACKAGE = "com.ccffee.mcuvolha"

        /** Its boot receiver, used to start it again without showing any UI. */
        const val WIFI_OWNER_RECEIVER = "com.ccffee.mcuvolha/.app.BootReceiver"

        val HOSTAPD_PATHS = listOf(
            "/data/vendor/wifi/hostapd/hostapd_ap0.conf",
            "/data/vendor/wifi/hostapd/hostapd.conf",
            "/data/misc/wifi/hostapd.conf",
            "/data/vendor/wifi/wpa/hostapd.conf",
        )

        val WAIT_STEPS_MILLIS = longArrayOf(120, 220, 360, 520, 760, 1000)
        const val WAIT_TIMEOUT_MILLIS = 3_200L
        const val SOFT_AP_TIMEOUT_MILLIS = 15_000L
        const val SOFT_AP_DOWN_TIMEOUT_MILLIS = 4_000L
        const val SOFT_AP_POLL_MILLIS = 500L
    }
}

private inline fun <T> ReentrantLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
