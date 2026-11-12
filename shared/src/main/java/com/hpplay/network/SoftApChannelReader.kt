package com.hpplay.network

import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Reads the live LocalOnlyHotspot channel from the framework's SoftApCallback stream.
 *
 * `WifiManager.registerLocalOnlyHotspotSoftApCallback` (Android 13 / API 33) reports the real
 * channel of the running SoftAP, which the reservation's [android.net.wifi.SoftApConfiguration]
 * hides on several ROMs. The callback class is a **class**, not an interface, so it cannot be
 * proxied with `java.lang.reflect.Proxy`; it is loaded reflectively and a matching interface
 * (`WifiManager.SoftApCallback`) is proxied instead, then registered with the framework.
 *
 * Every reflective step is optional: when the API is missing [register] returns null and callers
 * fall back to the driver-frequency reader.
 */
internal class SoftApChannelReader private constructor(
    private val wifiManager: WifiManager,
    private val unregisterMethod: Method,
) {
    private val lock = Object()

    @Volatile
    private var channel: Int = 0

    @Volatile
    private var proxy: Any? = null

    /** Waits up to [timeoutMillis] for the framework to report the live hotspot channel. */
    fun awaitChannel(timeoutMillis: Long): Int? {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        synchronized(lock) {
            while (channel <= 0) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) return null
                try {
                    lock.wait(remaining)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return channel
        }
    }

    fun close() {
        val active = proxy ?: return
        try {
            unregisterMethod.invoke(wifiManager, active)
        } catch (_: Throwable) {
            // Best effort: the reservation owns the real lifetime of the callback.
        }
    }

    private fun publish(value: Int) {
        if (value <= 0) return
        synchronized(lock) {
            if (channel <= 0) {
                channel = value
                lock.notifyAll()
            }
        }
    }

    private fun handle(method: Method, args: Array<Any?>?): Any? = when (method.name) {
        "onStateChanged", "onInfoChanged" -> {
            args?.forEach { argument ->
                if (argument != null &&
                    argument.javaClass.name == "android.net.wifi.SoftApConfiguration"
                ) {
                    publish(extractChannel(argument))
                }
            }
            null
        }
        "onConnectedClientsChanged" -> null
        "hashCode" -> System.identityHashCode(this)
        "equals" -> false
        "toString" -> "hplay-softap-callback"
        else -> null
    }

    private fun extractChannel(configuration: Any): Int {
        // API 36+: getChannels() returns a band -> channel map.
        try {
            val channels = configuration.javaClass.getMethod("getChannels")
                .invoke(configuration) as? android.util.SparseIntArray
            if (channels != null && channels.size() > 0) {
                val channel = channels.valueAt(0)
                if (channel > 0) return channel
            }
        } catch (_: Throwable) {
            // Not available before API 36; fall through to the channel getter.
        }
        // API 30..35: getChannel().
        return runCatching {
            configuration.javaClass.getMethod("getChannel").invoke(configuration) as? Int
        }.getOrNull() ?: 0
    }

    companion object {
        private const val TAG = "hplay-usb"

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun register(wifiManager: WifiManager, thread: android.os.HandlerThread): SoftApChannelReader? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return try {
                val callbackClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
                val registerMethod = WifiManager::class.java.getMethod(
                    "registerLocalOnlyHotspotSoftApCallback",
                    Executor::class.java,
                    callbackClass,
                )
                val unregisterMethod = WifiManager::class.java.getMethod(
                    "unregisterLocalOnlyHotspotSoftApCallback",
                    callbackClass,
                )
                val reader = SoftApChannelReader(wifiManager, unregisterMethod)
                val handler = Handler(Looper.getMainLooper())
                val invocationHandler = InvocationHandler { _, method, args ->
                    reader.handle(method, args)
                }
                val proxy = Proxy.newProxyInstance(
                    callbackClass.classLoader,
                    arrayOf(callbackClass),
                    invocationHandler,
                )
                reader.proxy = proxy
                registerMethod.invoke(wifiManager, HandlerExecutor(handler), proxy)
                reader
            } catch (error: Throwable) {
                Log.i(
                    TAG,
                    "Live hotspot channel unavailable " +
                        "(${error.javaClass.simpleName}); using driver/framework readback",
                )
                null
            }
        }
    }

    private class HandlerExecutor(private val handler: Handler) : Executor {
        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }
}
