package com.hpplay.network

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * Best-effort ADB pre-authorization for the wired CarPlay flow, run through the head unit's own
 * root adbd channel (see [RootShell]).
 *
 * Two ROM behaviors make the *system* dialogs unreliable on head units — the same ones Wplay's own
 * shell grants work around:
 *  - The USB permission dialog for a freshly attached iPhone can be suppressed (the ROM remembers
 *    a stale "deny"), so the app would wait forever on a grant the user never gets to give.
 *  - The VPN consent dialog is not shown at all on some ROMs (Huaqin / Wuling are the ones Wplay
 *    calls out), so `CarPlayVpnService.prepare()` never returns a usable intent and the wired
 *    bring-up stalls before the NCM tunnel is attached.
 *
 * Both are fixed by granting the matching app-op from a shell the app cannot otherwise reach. Every
 * command is idempotent, so running the whole set on each wired start is fine; failures are logged
 * and ignored because the dialogs are still the fallback when there is no adb channel.
 */
object CarPlayAdbGrants {
    private const val TAG = "hplay-usb"

    /**
     * App-ops and settings that let the wired path come up without a system dialog.
     *
     * `android:activate_vpn` is the app-op behind the VPN consent prompt; `android:get_usage_stats`
     * and `android:system_alert_window` cover the overlays the host draws; `WRITE_SETTINGS` is
     * needed by the tethering fallback. The accessibility entry is written by
     * [UsbAccessibilityAutoConfirm] and is included here only as a pointer.
     */
    fun commands(packageName: String): List<String> = listOf(
        "appops set $packageName android:activate_vpn allow",
        "appops set $packageName android:get_usage_stats allow",
        "appops set $packageName android:system_alert_window allow",
        "appops set $packageName WRITE_SETTINGS allow",
    )

    /**
     * 通过车机 loopback adb 通道执行任意一条 shell 命令（用于 CarLife 权限页的"一键授权"：
     * pm grant / appops set 等）。失败返回 false。
     */
    fun runShell(command: String): Boolean =
        try {
            RootShell.run(command)
            true
        } catch (_: IOException) {
            false
        }

    /**
     * Runs [commands] over the root adb channel.
     *
     * @return true when at least one command was accepted by a shell, false when no adb channel is
     *   available at all (the caller then simply relies on the system dialogs).
     */
    fun apply(context: Context, packageName: String = context.packageName): Boolean {
        if (!RootShell.isAvailable()) {
            Log.i(TAG, "no adb channel; USB/VPN app-ops left to the system dialogs")
            return false
        }
        var applied = false
        for (command in commands(packageName)) {
            try {
                RootShell.run(command)
                applied = true
            } catch (error: IOException) {
                Log.w(TAG, "adb grant failed: $command", error)
            }
        }
        Log.i(TAG, "wired CarPlay adb grants applied=$applied")
        return applied
    }
}
