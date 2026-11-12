package com.hpplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import android.util.Log
import android.annotation.SuppressLint
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Starts and stops the system SoftAP for the F510C "force 5 GHz hotspot" flow.
 *
 * `WifiManager.setWifiApEnabled` was removed in API 23 and `IWifiManager.startSoftAp` needs a
 * signature-level permission, so the only workable route is `ConnectivityManager.startTethering`.
 * On the head unit that method is not exposed on the public manager, so the hidden
 * `ConnectivityManager.mService` is used directly.
 *
 * Android 9 enforces the hidden-API policy on reflection: `getDeclaredMethod("startTethering", …)`
 * and the `TRANSACTION_startTethering` constants both throw, so on API 28 the interface is driven
 * through [IBinder.transact] with the codes from [TetheringWire] instead (see that class). The
 * reflective route below is kept as a fallback for other ROMs, and a plain [ResultReceiver] is used
 * rather than the framework callback class because the latter is an abstract class that
 * `java.lang.reflect.Proxy` cannot implement.
 */
internal class TetheringController(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Outcome reported by [startWifiTethering]. */
    enum class StartResult { STARTED, FAILED, UNSUPPORTED }

    /**
     * Asks the system to bring up the Wi-Fi SoftAP. Returns once the instruction has been sent (or
     * rejected); the real state change is reported through [onStarted]/[onFailed].
     */
    fun startWifiTethering(onStarted: () -> Unit, onFailed: () -> Unit): StartResult {
        val receiver = object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) {
                if (resultCode == 0) onStarted() else onFailed()
            }
        }
        return invokeStartTethering(receiver)
    }

    /** Asks the system to tear the Wi-Fi SoftAP down. Best-effort: failures are logged. */
    fun stopWifiTethering(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (stopTetheringViaBinder(manager)) return true
        return try {
            val service = connectivityService(manager) ?: return false
            val withCaller = declaredMethod(
                service.javaClass,
                "stopTethering",
                Int::class.javaPrimitiveType!!,
                String::class.java,
            )
            if (withCaller != null) {
                withCaller.isAccessible = true
                withCaller.invoke(service, TETHERING_WIFI, context.packageName)
                return true
            }
            val legacy = declaredMethod(
                service.javaClass,
                "stopTethering",
                Int::class.javaPrimitiveType!!,
            )
            if (legacy != null) {
                legacy.isAccessible = true
                legacy.invoke(service, TETHERING_WIFI)
                return true
            }
            if (manager != null) {
                val publicMethod = declaredMethod(
                    manager.javaClass,
                    "stopTethering",
                    Int::class.javaPrimitiveType!!,
                )
                if (publicMethod != null) {
                    publicMethod.isAccessible = true
                    publicMethod.invoke(manager, TETHERING_WIFI)
                    return true
                }
            }
            false
        } catch (error: Throwable) {
            Log.w(TAG, "stopTethering failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun invokeStartTethering(receiver: ResultReceiver): StartResult {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return StartResult.UNSUPPORTED
        startTetheringViaBinder(manager, receiver)?.let { return it }
        try {
            val service = connectivityService(manager)
            if (service != null) {
                val withCaller = declaredMethod(
                    service.javaClass,
                    "startTethering",
                    Int::class.javaPrimitiveType!!,
                    ResultReceiver::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    String::class.java,
                )
                if (withCaller != null) {
                    withCaller.isAccessible = true
                    withCaller.invoke(
                        service,
                        TETHERING_WIFI,
                        receiver,
                        false,
                        context.packageName,
                    )
                    return StartResult.STARTED
                }
                val legacy = declaredMethod(
                    service.javaClass,
                    "startTethering",
                    Int::class.javaPrimitiveType!!,
                    ResultReceiver::class.java,
                    Boolean::class.javaPrimitiveType!!,
                )
                if (legacy != null) {
                    legacy.isAccessible = true
                    legacy.invoke(service, TETHERING_WIFI, receiver, false)
                    return StartResult.STARTED
                }
            }
            val publicMethod = declaredMethod(
                manager.javaClass,
                "startTethering",
                Int::class.javaPrimitiveType!!,
                ResultReceiver::class.java,
                Boolean::class.javaPrimitiveType!!,
            )
            if (publicMethod != null) {
                publicMethod.isAccessible = true
                publicMethod.invoke(manager, TETHERING_WIFI, receiver, false)
                return StartResult.STARTED
            }
            Log.w(TAG, "no usable startTethering signature was found")
            return StartResult.UNSUPPORTED
        } catch (error: Throwable) {
            Log.w(TAG, "startTethering failed: ${error.javaClass.simpleName}")
            return StartResult.FAILED
        }
    }

    // --- raw binder route (the only one Android 9 allows) ----------------------------------------

    /**
     * Sends `startTethering` straight over the binder.
     *
     * Returns null when the route does not apply — not API 28, no binder to talk to, or the
     * transaction itself failed to send — so the caller falls back to reflection.
     */
    private fun startTetheringViaBinder(
        manager: ConnectivityManager,
        receiver: ResultReceiver,
    ): StartResult? {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.P) return null
        val binder = connectivityBinder(manager) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            TetheringWire.writeStartTethering(parcelWriter(data), context.packageName) {
                receiver.writeToParcel(data, 0)
            }
            binder.transact(TetheringWire.TX_START_TETHERING, data, reply, 0)
            reply.readException()
            StartResult.STARTED
        } catch (error: Throwable) {
            Log.w(TAG, "startTethering via binder failed: ${error.javaClass.simpleName}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Sends `stopTethering` straight over the binder; false when the route does not apply. */
    private fun stopTetheringViaBinder(manager: ConnectivityManager?): Boolean {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.P) return false
        val binder = connectivityBinder(manager) ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            TetheringWire.writeStopTethering(parcelWriter(data), context.packageName)
            binder.transact(TetheringWire.TX_STOP_TETHERING, data, reply, 0)
            reply.readException()
            true
        } catch (error: Throwable) {
            Log.w(TAG, "stopTethering via binder failed: ${error.javaClass.simpleName}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Finds a binder for the connectivity service, preferring the plain `ServiceManager` lookup.
     *
     * `ServiceManager.getService("connectivity")` is a greylist call that works on Android 9 even
     * for a modern `targetSdk` (verified on a real device), and it needs no private fields at all.
     * Reading `ConnectivityManager.mService` → `mRemote` is kept as a fallback for ROMs where the
     * service name differs; that route is still allowed on API 28 (only method *lookup* is blocked),
     * and the proxy's `mRemote` field is a plain `BinderProxy` with a public `transact`.
     */
    private fun connectivityBinder(manager: ConnectivityManager?): IBinder? =
        serviceManagerBinder() ?: remoteBinder(manager)

    private fun serviceManagerBinder(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        getService.invoke(null, CONNECTIVITY_SERVICE) as? IBinder
    } catch (error: Throwable) {
        Log.i(TAG, "ServiceManager.getService(connectivity) unavailable: ${error.javaClass.simpleName}")
        null
    }

    private fun remoteBinder(manager: ConnectivityManager?): IBinder? {
        val service = manager?.let { connectivityService(it) } ?: return null
        return try {
            val field = service.javaClass.getDeclaredField("mRemote")
            field.isAccessible = true
            field.get(service) as? IBinder
        } catch (error: Throwable) {
            Log.i(TAG, "IConnectivityManager binder unavailable: ${error.javaClass.simpleName}")
            null
        }
    }

    // Last-resort fallback for ROMs whose IConnectivityManager service name differs. It is
    // only reached on API 28 (this head unit), where reflective field reads are still allowed;
    // the whole lookup is wrapped in try/catch and yields null when the platform blocks it.
    @SuppressLint("SoonBlockedPrivateApi")
    private fun connectivityService(manager: ConnectivityManager): Any? = try {
        val field: Field = ConnectivityManager::class.java.getDeclaredField("mService")
        field.isAccessible = true
        field.get(manager)
    } catch (error: Throwable) {
        Log.i(TAG, "ConnectivityManager.mService unavailable: ${error.javaClass.simpleName}")
        null
    }

    /** Bridges a [Parcel] to [TetheringWire.Writer] so the wire order stays testable off-device. */
    private fun parcelWriter(parcel: Parcel): TetheringWire.Writer = object : TetheringWire.Writer {
        override fun writeInterfaceToken(name: String) = parcel.writeInterfaceToken(name)
        override fun writeInt(value: Int) = parcel.writeInt(value)
        override fun writeString(value: String?) = parcel.writeString(value)
    }

    private fun declaredMethod(clazz: Class<*>, name: String, vararg parameters: Class<*>): Method? =
        try {
            clazz.getDeclaredMethod(name, *parameters)
        } catch (_: NoSuchMethodException) {
            null
        }

    private companion object {
        const val TAG = "hplay-usb"

        /** `Context.CONNECTIVITY_SERVICE`, the `ServiceManager` name for `IConnectivityManager`. */
        const val CONNECTIVITY_SERVICE = "connectivity"

        /** `ConnectivityManager.TETHERING_WIFI`. */
        const val TETHERING_WIFI = 0
    }
}
