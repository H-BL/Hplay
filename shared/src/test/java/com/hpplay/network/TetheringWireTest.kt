package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exact byte order of the raw `IConnectivityManager` transactions.
 *
 * The order is easy to get subtly wrong — an `in` parcelable needs a presence `int` before its body,
 * and dropping it shifts every later value so `callingPkg` reads back null and the framework rejects
 * the call. This was found on a real Android 9 device, so it is locked down here.
 */
class TetheringWireTest {
    private class RecordingWriter : TetheringWire.Writer {
        val calls = mutableListOf<String>()
        override fun writeInterfaceToken(name: String) {
            calls += "token:$name"
        }

        override fun writeInt(value: Int) {
            calls += "int:$value"
        }

        override fun writeString(value: String?) {
            calls += "string:$value"
        }
    }

    @Test
    fun startTetheringWritesTheAidlLayoutInOrder() {
        val writer = RecordingWriter()
        var receiverWritten = false
        TetheringWire.writeStartTethering(writer, "com.hpplay") {
            receiverWritten = true
            writer.calls += "receiver"
        }
        assertEquals(
            listOf(
                "token:${TetheringWire.DESCRIPTOR}",
                "int:${TetheringWire.TETHERING_WIFI}",
                "int:1", // the ResultReceiver is present, so AIDL writes it untyped next
                "receiver",
                "int:0", // showProvisioningUi = false
                "string:com.hpplay",
            ),
            writer.calls,
        )
        assertEquals(true, receiverWritten)
    }

    @Test
    fun stopTetheringWritesTheAidlLayoutInOrder() {
        val writer = RecordingWriter()
        TetheringWire.writeStopTethering(writer, "com.hpplay")
        assertEquals(
            listOf(
                "token:${TetheringWire.DESCRIPTOR}",
                "int:${TetheringWire.TETHERING_WIFI}",
                "string:com.hpplay",
            ),
            writer.calls,
        )
    }

    @Test
    fun transactionCodesMatchTheAndroidNineAidlPositions() {
        // startTethering is the 24th and stopTethering the 25th method in IConnectivityManager.aidl
        // on Android 9 (FIRST_CALL_TRANSACTION is 1). Both were confirmed against a real device.
        assertEquals(24, TetheringWire.TX_START_TETHERING)
        assertEquals(25, TetheringWire.TX_STOP_TETHERING)
        assertEquals(0, TetheringWire.TETHERING_WIFI)
    }
}
