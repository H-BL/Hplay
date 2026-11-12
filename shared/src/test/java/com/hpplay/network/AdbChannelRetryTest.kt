package com.hpplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * The retry decision guards against two opposite mistakes: retrying a command that would fail the
 * same way twice (or whose output is huge), and *not* retrying a dropped socket, which would leave
 * the feature dead until the app restarts. Both directions are pinned here.
 */
class AdbChannelRetryTest {
    @Test
    fun connectionErrorsAreTransient() {
        assertTrue(AdbChannelRetry.isTransportFailure(ConnectException("refused")))
        assertTrue(AdbChannelRetry.isTransportFailure(SocketException("reset")))
        assertTrue(AdbChannelRetry.isTransportFailure(SocketTimeoutException("timed out")))
        assertTrue(AdbChannelRetry.isTransportFailure(EOFException("closed")))
    }

    @Test
    fun aWrappedConnectionErrorIsStillTransient() {
        val wrapped = IOException("handshake failed", SocketException("broken pipe"))
        assertTrue(AdbChannelRetry.isTransportFailure(wrapped))
    }

    @Test
    fun theNoChannelMessageIsTransient() {
        assertTrue(AdbChannelRetry.isTransportFailure(IOException(AdbChannelRetry.NO_CHANNEL_MESSAGE)))
    }

    @Test
    fun anOversizedReplyIsNotRetried() {
        val error = IOException("${AdbChannelRetry.OUTPUT_LIMIT_MESSAGE} 262144 characters")
        assertFalse(AdbChannelRetry.isTransportFailure(error))
    }

    @Test
    fun aPlainCommandFailureIsNotRetried() {
        assertFalse(AdbChannelRetry.isTransportFailure(IOException("unexpected adb stream reply 0x0")))
    }
}
