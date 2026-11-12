package com.hpplay.network

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * Turns on this app's accessibility services ([com.hpplay.UsbAutoConfirmService] and
 * [com.hpplay.StartupKeeperService]) without sending the user through the system accessibility
 * settings screen.
 *
 * The services are declared by the app and, like every accessibility service, stay dormant until
 * they are listed in `secure.enabled_accessibility_services`. On a head unit the settings screen is
 * hard to reach, so they are raised through the head unit's own root adbd channel (see
 * [RootShell]) — the same trick Wplay uses for its USB auto-confirm.
 *
 * The list is a colon-separated set and `settings put` replaces it wholesale, so the current value
 * is read back and the component appended rather than clobbering whatever else the car had enabled.
 */
object UsbAccessibilityAutoConfirm {
    private const val TAG = "hplay-usb"

    /** The USB-prompt auto-confirmer, the one enabled by [apply]. */
    private const val USB_SERVICE_CLASS = "com.hpplay.UsbAutoConfirmService"

    /** The wake-from-sleep keepalive, enabled by [applyKeeper]. */
    private const val KEEPER_SERVICE_CLASS = "com.hpplay.StartupKeeperService"

    /**
     * Fully-qualified accessibility service component for [packageName].
     *
     * Built by hand so the exact string (and therefore the `settings put` payload) is assertable in a
     * plain unit test.
     */
    fun component(packageName: String, serviceClass: String = USB_SERVICE_CLASS): String =
        "$packageName/$serviceClass"

    /** Fully-qualified USB auto-confirm component inside [context]'s package. */
    fun component(context: Context): String = component(context.packageName)

    /**
     * The read-modify-write that enables a service.
     *
     * Kept as a pure function of the current setting value so the exact bytes can be asserted in a
     * unit test; [apply] / [applyKeeper] are the thin Android wrappers.
     */
    fun commands(
        packageName: String,
        current: String,
        serviceClass: String = USB_SERVICE_CLASS,
    ): List<String> {
        val component = component(packageName, serviceClass)
        val enabled = current.split(':').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (component !in enabled) enabled.add(component)
        return listOf(
            "settings put secure enabled_accessibility_services \"${enabled.joinToString(":")}\"",
            "settings put secure accessibility_enabled 1",
        )
    }

    /**
     * Enables the USB auto-confirm service over the root adb channel.
     *
     * @return true when the service was (already) enabled or the commands were accepted, false when
     *   there is no adb channel and the user must enable it from the accessibility settings screen.
     */
    fun apply(context: Context): Boolean = enable(context, USB_SERVICE_CLASS)

    /** Enables the wake-from-sleep keepalive service; same contract as [apply]. */
    fun applyKeeper(context: Context): Boolean = enable(context, KEEPER_SERVICE_CLASS)

    /** True when the app's USB auto-confirm service is currently listed as enabled. */
    fun isEnabled(context: Context): Boolean = isServiceEnabled(context, USB_SERVICE_CLASS)

    /** True when the app's wake-from-sleep keepalive service is currently listed as enabled. */
    fun isKeeperEnabled(context: Context): Boolean =
        isServiceEnabled(context, KEEPER_SERVICE_CLASS)

    private fun enable(context: Context, serviceClass: String): Boolean {
        if (isServiceEnabled(context, serviceClass)) {
            Log.i(TAG, "accessibility service already enabled: $serviceClass")
            return true
        }
        if (!RootShell.isAvailable()) {
            Log.i(TAG, "no adb channel; accessibility service must be enabled from settings")
            return false
        }
        return try {
            val current = RootShell.run(READ_COMMAND).trim()
            for (command in commands(context.packageName, current, serviceClass)) {
                RootShell.run(command)
            }
            Log.i(TAG, "accessibility service enabled via adb: $serviceClass")
            true
        } catch (error: IOException) {
            Log.w(TAG, "could not enable accessibility service $serviceClass", error)
            false
        }
    }

    private fun isServiceEnabled(context: Context, serviceClass: String): Boolean {
        val raw = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val component = component(context.packageName, serviceClass)
        return raw.split(':').any { it.trim().equals(component, ignoreCase = true) }
    }

    private const val READ_COMMAND = "settings get secure enabled_accessibility_services"
}
