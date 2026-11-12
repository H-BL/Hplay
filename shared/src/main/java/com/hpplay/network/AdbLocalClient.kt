package com.hpplay.network

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs shell commands on the head unit through its own adbd over loopback.
 *
 * The F510C starts adbd as root on TCP 5557 (older builds use 5555) and does not ask the host to
 * authenticate, so connecting to `127.0.0.1` and opening the `shell:` service yields a root shell
 * that needs neither Magisk nor `su`. This is the same channel the vendor launcher uses for
 * privileged work, and it is what makes the "force 5 GHz hotspot" flow possible on a car where the
 * user forbids Magisk's `su`.
 *
 * Every call opens a fresh connection: the flow issues only a handful of commands, and a stateless
 * client cannot be left holding a half-read stream after a timeout.
 */
internal class AdbLocalClient(
    private val ports: IntArray = intArrayOf(DEFAULT_PORT, LEGACY_PORT),
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) {
    /** Loopback only: adbd is on the head unit itself. */
    private val host: String = LOOPBACK

    @Volatile
    private var resolvedPort: Int = -1

    /** The port that answered the handshake, or -1 when the channel has not been probed yet. */
    val port: Int get() = resolvedPort

    /**
     * Confirms that a usable adb channel exists and remembers which port answered.
     *
     * A refused connection (nothing listening) is treated as "no channel", but a reachable adbd that
     * refuses us — typically because it demands authentication — is rethrown, so the caller can tell
     * "no adb here" apart from "adbd is here but we are not allowed in".
     *
     * @return the working port, or -1 when adbd is unreachable.
     * @throws IOException when a reachable adbd rejects the handshake.
     */
    fun probe(): Int {
        resolvedPort.takeIf { it > 0 }?.let { return it }
        var reachableButRejected: IOException? = null
        for (port in ports) {
            try {
                openSession(port).use { session ->
                    resolvedPort = port
                    return port
                }
            } catch (error: IOException) {
                if (isConnectionRefused(error)) {
                    Log.i(TAG, "no adbd on 127.0.0.1:$port: ${error.message}")
                } else {
                    Log.w(TAG, "adbd on 127.0.0.1:$port rejected the handshake: ${error.message}")
                    reachableButRejected = error
                }
            }
        }
        reachableButRejected?.let { throw it }
        return -1
    }

    private fun isConnectionRefused(error: IOException): Boolean {
        val cause = generateSequence<Throwable>(error) { it.cause }
            .firstOrNull { it is java.net.ConnectException }
        return cause != null
    }

    /**
     * Runs [command] through `shell:` and returns its combined output.
     *
     * @throws IOException when no channel is available, the command cannot be opened, or the peer
     *   closes the stream without sending a result within [readTimeoutMillis].
     */
    fun shell(command: String): String {
        val port = if (resolvedPort > 0) resolvedPort else probe()
        if (port <= 0) throw IOException("no adb channel on the head unit")

        return openSession(port).use { session -> session.shell(command) }
    }

    private fun openSession(port: Int): AdbSession {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            socket.soTimeout = readTimeoutMillis
            val session = AdbSession(socket)
            session.handshake()
            return session
        } catch (error: IOException) {
            runCatching { socket.close() }
            throw error
        }
    }

    /** One connection to adbd, already past the `CNXN` handshake. */
    private class AdbSession(private val socket: Socket) : AutoCloseable {
        private val input = DataInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()

        /** Sends `CNXN` and waits for the peer banner, failing loudly when auth is demanded. */
        fun handshake() {
            write(AdbProtocol.CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_DATA, AdbProtocol.HOST_BANNER)
            while (true) {
                val message = read()
                when (message.command) {
                    AdbProtocol.CNXN -> return
                    AdbProtocol.AUTH -> throw IOException(
                        "adbd requires authentication; enable ADB debugging for this app first",
                    )
                    else -> throw IOException(
                        "unexpected adb handshake reply 0x${Integer.toHexString(message.command)}",
                    )
                }
            }
        }

        /** Opens the `shell:` service for [command] and drains its output until the peer closes. */
        fun shell(command: String): String {
            val localId = LOCAL_ID
            write(AdbProtocol.OPEN, localId, 0, AdbProtocol.shellService(command))
            val text = StringBuilder()
            var remoteId = -1
            while (true) {
                val message = read()
                when (message.command) {
                    AdbProtocol.OKAY -> {
                        if (remoteId < 0) remoteId = message.arg0
                    }
                    AdbProtocol.WRTE -> {
                        if (remoteId < 0) remoteId = message.arg0
                        text.append(message.payloadText())
                        if (text.length > MAX_OUTPUT_CHARS) {
                            throw IOException("adb shell output exceeded $MAX_OUTPUT_CHARS characters")
                        }
                        acknowledge(localId, message.arg0)
                    }
                    AdbProtocol.CLSE -> {
                        acknowledge(localId, message.arg0)
                        return text.toString()
                    }
                    else -> throw IOException(
                        "unexpected adb stream reply 0x${Integer.toHexString(message.command)}",
                    )
                }
            }
        }

        /**
         * Acks a stream message best-effort. adbd may close its side immediately after the final
         * `CLSE`, so a write can hit a reset socket; the command's output is already in hand by then
         * and must not be discarded over a failed acknowledgement.
         */
        private fun acknowledge(localId: Int, remoteId: Int) {
            try {
                write(AdbProtocol.OKAY, localId, remoteId)
            } catch (error: IOException) {
                Log.d(TAG, "adb ack after stream close was refused: ${error.message}")
            }
        }

        private fun read(): AdbProtocol.Message {
            val header = ByteArray(AdbProtocol.HEADER_LENGTH)
            input.readFully(header)
            val decoded = AdbProtocol.decodeHeader(header)
            val payload = if (decoded.length > 0) ByteArray(decoded.length).also { input.readFully(it) } else ByteArray(0)
            return AdbProtocol.Message(decoded.command, decoded.arg0, decoded.arg1, payload)
        }

        private fun write(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
            output.write(AdbProtocol.encode(command, arg0, arg1, payload))
            output.flush()
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    internal companion object {
        const val TAG = "hplay-usb"
        const val LOOPBACK = "127.0.0.1"
        const val DEFAULT_PORT = 5557
        const val LEGACY_PORT = 5555
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 4_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 20_000
        const val MAX_OUTPUT_CHARS = 256 * 1024
        const val LOCAL_ID = 1
    }
}
