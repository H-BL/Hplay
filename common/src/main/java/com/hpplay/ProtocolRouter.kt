package com.hpplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Sends the launcher / boot / wake paths to the activity that matches the active [Protocol].
 *
 * CarPlay and CarLife cannot share a process (they are different engines), which is exactly why the
 * launcher goes through a router instead of hosting both in one activity. For CarLife the engine may
 * come from either:
 *
 *  1. our own `:carlife` module activity (when that module is part of the build), or
 *  2. a CarLife vehicle engine already installed on the head unit (a separate package).
 *
 * When CarLife is served by an external engine there is no Hplay settings UI on its screen, so the
 * router routes through [ProtocolHomeActivity] — a small pad that can open CarLife and change the
 * protocol. When no CarLife engine exists at all, the router falls back to CarPlay.
 */
object ProtocolRouter {
    private const val TAG = "ProtocolRouter"

    /** CarPlay host activity (in this module). */
    const val CARPLAY_ACTIVITY = "com.hpplay.CarPlayHostActivity"

    /** CarLife host activity provided by the optional `:carlife` module. */
    const val CARLIFE_ACTIVITY = "com.hpplay.carlife.CarlifeHostActivity"

    /** CarLink host activity provided by the `:carlink` module (native implementation). */
    const val CARLINK_ACTIVITY = "com.hpplay.carlink.CarlinkHostActivity"

    /** Lightweight launcher pad used when CarLife is served by an external engine with no Hplay UI. */
    const val PROTOCOL_HOME_ACTIVITY = "com.hpplay.ProtocolHomeActivity"

    /** External CarLife engine packages we know how to hand off to ("package/activity"). */
    private val EXTERNAL_CARLIFE_ENGINES = listOf(
        "com.muyetuge.carlifevehicle/com.muyetuge.carlifevehicle.activity.MainActivity",
    )

    /** Our own CarLife activity, if the `:carlife` module is in this build. */
    fun ownCarlifeComponent(context: Context): ComponentName? =
        if (isActivityAvailable(context, context.packageName, CARLIFE_ACTIVITY)) {
            ComponentName(context.packageName, CARLIFE_ACTIVITY)
        } else {
            null
        }

    /** An installed external CarLife engine activity, if any. */
    fun externalCarlifeComponent(context: Context): ComponentName? =
        EXTERNAL_CARLIFE_ENGINES.firstNotNullOfOrNull { entry ->
            val pkg = entry.substringBefore('/')
            val cls = entry.substringAfter('/')
            if (isActivityAvailable(context, pkg, cls)) ComponentName(pkg, cls) else null
        }

    /** Our own CarLink host activity, if the `:carlink` module is in this build. */
    fun ownCarlinkComponent(context: Context): ComponentName? =
        if (isActivityAvailable(context, context.packageName, CARLINK_ACTIVITY)) {
            ComponentName(context.packageName, CARLINK_ACTIVITY)
        } else {
            null
        }

    /** The component the launcher should open for [protocol]. */
    fun resolveProtocolComponent(context: Context, protocol: Protocol): ComponentName {
        if (protocol == Protocol.CARLIFE) {
            ownCarlifeComponent(context)?.let { return it }
            if (externalCarlifeComponent(context) != null) {
                // External engine: land on the pad so the protocol switch stays reachable.
                return ComponentName(context.packageName, PROTOCOL_HOME_ACTIVITY)
            }
            Log.w(TAG, "No CarLife engine available; falling back to CarPlay")
        }
        if (protocol == Protocol.CARLINK) {
            if (!PlatformCapabilities.supportsCarLink) {
                Log.w(TAG, "CarLink needs Android 8.1+ (libovmsink.so API 27); falling back to CarPlay")
            } else {
                ownCarlinkComponent(context)?.let { return it }
                Log.w(TAG, "No CarLink module available; falling back to CarPlay")
            }
        }
        return ComponentName(context.packageName, CARPLAY_ACTIVITY)
    }

    /** Launches the actual CarLife engine (module or external), or CarPlay if none. Used by the pad. */
    fun launchCarlifeEngine(context: Context) {
        val component = ownCarlifeComponent(context)
            ?: externalCarlifeComponent(context)
            ?: ComponentName(context.packageName, CARPLAY_ACTIVITY)
        start(context, component)
    }

    /** Launches the host activity for the currently saved protocol (boot / wake paths). */
    fun launchCurrent(context: Context) {
        val protocol = AirPlayPersistence.loadProtocol(context)
        start(context, resolveProtocolComponent(context, protocol))
    }

    /** Whether CarLife can actually be launched right now (own module or an installed engine). */
    fun isCarlifeAvailable(context: Context): Boolean =
        ownCarlifeComponent(context) != null || externalCarlifeComponent(context) != null

    /** Whether CarLink can actually be launched right now (its module is in this build + 8.1+). */
    fun isCarlinkAvailable(context: Context): Boolean =
        PlatformCapabilities.supportsCarLink && ownCarlinkComponent(context) != null

    private fun start(context: Context, component: ComponentName) {
        val intent = Intent().apply {
            this.component = component
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(intent)
        } catch (error: Throwable) {
            Log.w(TAG, "could not launch $component", error)
        }
    }

    private fun isActivityAvailable(context: Context, pkg: String, cls: String): Boolean = try {
        context.packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
        true
    } catch (error: Throwable) {
        false
    }
}
