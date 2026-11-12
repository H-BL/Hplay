package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end checks for [AdbLocalClient] against an in-process stand-in for the head unit's adbd.
 *
 * The real car runs adbd as root on 5557 and does not ask for authentication, so the fake server
 * mirrors exactly that: it accepts a `CNXN`, answers with its own banner, and turns `OPEN("shell:…")`
 * into one `WRTE` carrying the canned output followed by `CLSE`. A second mode answers `AUTH` to
 * prove the client fails loudly when a device does demand a key.
 */
class AdbLocalClientTest {
    @Test
    fun shellReturnsOutputFromTheAdbChannel() {
        FakeAdbd(output = "uid=0(root) gid=0(root)\n").use { server ->
            val client = AdbLocalClient(ports = intArrayOf(server.port))
            assertEquals("uid=0(root) gid=0(root)\n", client.shell("id"))
            assertEquals(server.port, client.port)
        }
    }

    @Test
    fun shellSendsTheCommandThroughTheShellService() {
        FakeAdbd(output = "00530055\n").use { server ->
            val client = AdbLocalClient(ports = intArrayOf(server.port))
            client.shell("service call wifi 28")
            assertEquals("service call wifi 28", server.lastService?.removeSuffix("\u0000")?.removePrefix("shell:"))
        }
    }

    @Test
    fun probeReportsTheWorkingPortAndCachesIt() {
        FakeAdbd(output = "ok\n").use { server ->
            val client = AdbLocalClient(ports = intArrayOf(server.port))
            assertEquals(server.port, client.probe())
            assertEquals(server.port, client.probe())
        }
    }

    @Test
    fun probeReturnsMinusOneWhenNothingIsListening() {
        val dead = ServerSocket(0).use { it.localPort }
        val client = AdbLocalClient(ports = intArrayOf(dead), connectTimeoutMillis = 500)
        assertEquals(-1, client.probe())
    }

    @Test
    fun shellThrowsWhenAdbdDemandsAuthentication() {
        FakeAdbd(output = "", requireAuth = true).use { server ->
            val client = AdbLocalClient(ports = intArrayOf(server.port))
            val error = assertThrows(IOException::class.java) { client.shell("id") }
            assertTrue(error.message!!.contains("authentication"))
        }
    }

    @Test
    fun fallsBackToTheLegacyPortWhenThePrimaryIsDead() {
        val dead = ServerSocket(0).use { it.localPort }
        FakeAdbd(output = "legacy\n").use { server ->
            val client = AdbLocalClient(ports = intArrayOf(dead, server.port), connectTimeoutMillis = 500)
            assertEquals("legacy\n", client.shell("id"))
            assertEquals(server.port, client.port)
        }
    }

    /** A one-connection-at-a-time fake adbd speaking just enough of the protocol. */
    private class FakeAdbd(
        private val output: String,
        private val requireAuth: Boolean = false,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        private val thread: Thread
        private val handled = AtomicInteger(0)

        val port: Int get() = server.localPort

        @Volatile
        var lastService: String? = null
            private set

        init {
            thread = Thread {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        return@Thread
                    }
                    runCatching { serve(socket) }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun serve(socket: Socket) {
            socket.use { connection ->
                val input = DataInputStream(connection.getInputStream())
                val outputStream = connection.getOutputStream()

                val cnxn = readMessage(input)
                if (cnxn.command != AdbProtocol.CNXN) {
                    throw IOException("expected CNXN, got ${cnxn.command}")
                }
                if (requireAuth) {
                    outputStream.write(AdbProtocol.encode(AdbProtocol.AUTH, 1, 0, ByteArray(0)))
                    outputStream.flush()
                    return
                }
                outputStream.write(
                    AdbProtocol.encode(
                        AdbProtocol.CNXN,
                        AdbProtocol.VERSION,
                        AdbProtocol.MAX_DATA,
                        "device::ro.product.name=fake;\u0000".toByteArray(),
                    ),
                )
                outputStream.flush()

                val open = readMessage(input)
                if (open.command != AdbProtocol.OPEN) {
                    throw IOException("expected OPEN, got ${open.command}")
                }
                lastService = open.payloadText()
                val remoteId = handled.incrementAndGet()

                outputStream.write(AdbProtocol.encode(AdbProtocol.OKAY, remoteId, 0, ByteArray(0)))
                if (output.isNotEmpty()) {
                    outputStream.write(
                        AdbProtocol.encode(
                            AdbProtocol.WRTE,
                            remoteId,
                            0,
                            output.toByteArray(Charsets.UTF_8),
                        ),
                    )
                    outputStream.flush()
                    // Real adbd waits for the host's ack before moving on; mirror that.
                    readMessage(input)
                }
                outputStream.write(AdbProtocol.encode(AdbProtocol.CLSE, remoteId, 0, ByteArray(0)))
                outputStream.flush()
            }
        }

        private fun readMessage(input: DataInputStream): AdbProtocol.Message {
            val header = ByteArray(AdbProtocol.HEADER_LENGTH)
            input.readFully(header)
            val decoded = AdbProtocol.decodeHeader(header)
            val payload = if (decoded.length > 0) {
                ByteArray(decoded.length).also { input.readFully(it) }
            } else {
                ByteArray(0)
            }
            return AdbProtocol.Message(decoded.command, decoded.arg0, decoded.arg1, payload)
        }

        override fun close() {
            runCatching { server.close() }
            thread.join(1_000)
        }
    }
}
