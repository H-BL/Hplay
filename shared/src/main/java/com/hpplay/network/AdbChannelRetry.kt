package com.hpplay.network

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Decides whether an adb-channel failure is worth retrying after dropping the cached connection.
 *
 * The channel to the head unit's adbd is a plain loopback TCP socket, and it can drop for reasons
 * that have nothing to do with the command: adbd restarts, the port moves between 5557 and 5555, or
 * the socket is reset while the device is busy raising the SoftAP. When that happens the cached
 * client in [RootShell] points at a dead socket and every later command fails, so the feature looks
 * permanently broken until the app restarts. Retrying once on a transport failure is what makes it
 * self-heal.
 *
 * Protocol failures are deliberately *not* retried: an over-long reply or a bad handshake would fail
 * the same way twice, and a command whose output is huge must not be run a second time. Only the
 * connection-level errors (and "no channel yet") are treated as transient.
 *
 * Kept free of Android types so the classification can be unit-tested.
 */
internal object AdbChannelRetry {
    /** Message prefix [AdbLocalClient] uses when a reply exceeds its cap; never retried. */
    const val OUTPUT_LIMIT_MESSAGE = "adb shell output exceeded"

    /** Message [RootShell] uses when there is no channel to talk to; retried after a re-probe. */
    const val NO_CHANNEL_MESSAGE = "no adb channel on the head unit"

    /** True when [error] is a connection-level failure that a fresh probe could recover from. */
    fun isTransportFailure(error: IOException): Boolean {
        val message = error.message.orEmpty()
        if (message.startsWith(OUTPUT_LIMIT_MESSAGE)) return false
        if (message.contains(NO_CHANNEL_MESSAGE)) return true
        return generateSequence<Throwable>(error) { it.cause }.any {
            it is ConnectException ||
                it is SocketException ||
                it is SocketTimeoutException ||
                it is EOFException
        }
    }
}
