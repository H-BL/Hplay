package com.hpplay.network

/**
 * Wire encoding for the hidden `IConnectivityManager` binder interface.
 *
 * Android 9 enforces the hidden-API policy on *reflection*: on the head unit
 * `getDeclaredMethod("startTethering", …)` and even the `TRANSACTION_startTethering` constants throw
 * `NoSuchMethodException`/`NoSuchFieldException`, so the reflective route [TetheringController] used
 * to take silently finds nothing. The policy does **not** touch `IBinder.transact`, so the interface
 * is driven through the binder directly instead.
 *
 * The transaction codes are the 1-based positions of the methods in the Android 9
 * `IConnectivityManager.aidl` (`FIRST_CALL_TRANSACTION` is 1): `startTethering` is the 24th method
 * and `stopTethering` the 25th. That is why [TetheringController] only uses this route on API 28 —
 * a later release inserts methods and shifts the numbers.
 *
 * The byte order here mirrors what `IConnectivityManager.aidl` tells the AIDL compiler to emit:
 * an `in ResultReceiver` parameter is written as a presence `int` followed by the receiver's own
 * `writeToParcel` (no class-name prefix). Omitting the presence `int` shifts every following value,
 * which is how `callingPkg` ends up null and the call is rejected.
 */
internal object TetheringWire {
    /** `IConnectivityManager` binder interface descriptor. */
    const val DESCRIPTOR = "android.net.IConnectivityManager"

    /** `ConnectivityManager.TETHERING_WIFI`. */
    const val TETHERING_WIFI = 0

    /** Transaction for `startTethering(int, ResultReceiver, boolean, String)`. */
    const val TX_START_TETHERING = 24

    /** Transaction for `stopTethering(int, String)`. */
    const val TX_STOP_TETHERING = 25

    /**
     * The tiny slice of `android.os.Parcel` the encoders need. A seam so the exact write order can
     * be asserted in a plain JVM unit test, where a real `Parcel` is unavailable.
     */
    interface Writer {
        fun writeInterfaceToken(name: String)
        fun writeInt(value: Int)
        fun writeString(value: String?)
    }

    /** Encodes `startTethering(TETHERING_WIFI, receiver, false, packageName)`. */
    fun writeStartTethering(data: Writer, packageName: String, writeReceiver: () -> Unit) {
        data.writeInterfaceToken(DESCRIPTOR)
        data.writeInt(TETHERING_WIFI)
        data.writeInt(1) // the receiver is non-null, so AIDL writes it untyped on the next line
        writeReceiver()
        data.writeInt(0) // showProvisioningUi = false
        data.writeString(packageName)
    }

    /** Encodes `stopTethering(TETHERING_WIFI, packageName)`. */
    fun writeStopTethering(data: Writer, packageName: String) {
        data.writeInterfaceToken(DESCRIPTOR)
        data.writeInt(TETHERING_WIFI)
        data.writeString(packageName)
    }
}
