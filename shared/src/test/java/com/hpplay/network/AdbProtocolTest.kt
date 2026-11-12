package com.hpplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Framing-level checks for the ADB smart-socket protocol used to reach the head unit's adbd. */
class AdbProtocolTest {
    @Test
    fun encodeLaysOutTheDocumentedHeader() {
        val payload = "hi".toByteArray(Charsets.UTF_8)
        val frame = AdbProtocol.encode(AdbProtocol.WRTE, 1, 7, payload)

        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbProtocol.WRTE, buffer.int)
        assertEquals(1, buffer.int)
        assertEquals(7, buffer.int)
        assertEquals(2, buffer.int)
        assertEquals(AdbProtocol.crc32(payload), buffer.int)
        assertEquals(AdbProtocol.WRTE.inv(), buffer.int)
        assertArrayEquals(payload, frame.copyOfRange(24, 26))
    }

    @Test
    fun encodeOfEmptyPayloadHasNoBody() {
        val frame = AdbProtocol.encode(AdbProtocol.OKAY, 3, 0)
        assertEquals(AdbProtocol.HEADER_LENGTH, frame.size)
        assertEquals(AdbProtocol.OKAY, AdbProtocol.decode(frame).command)
    }

    @Test
    fun decodeRoundTripsPayloadAndFields() {
        val message = AdbProtocol.Message(
            command = AdbProtocol.WRTE,
            arg0 = 42,
            arg1 = 5,
            payload = "hello world".toByteArray(Charsets.UTF_8),
        )
        val decoded = AdbProtocol.decode(
            AdbProtocol.encode(message.command, message.arg0, message.arg1, message.payload),
        )
        assertEquals(message, decoded)
        assertEquals("hello world", decoded.payloadText())
    }

    @Test
    fun shellServiceIsAnOpenStringEndingInNul() {
        val service = AdbProtocol.shellService("id")
        assertArrayEquals("shell:id\u0000".toByteArray(Charsets.UTF_8), service)
    }

    @Test
    fun hostBannerIsWellFormed() {
        assertArrayEquals("host::\u0000".toByteArray(Charsets.UTF_8), AdbProtocol.HOST_BANNER)
        assertTrue(AdbProtocol.HOST_BANNER.last() == 0.toByte())
    }

    @Test
    fun crc32MatchesJavaUtilCrc() {
        val payload = "service call wifi 28".toByteArray(Charsets.UTF_8)
        val crc = java.util.zip.CRC32().apply { update(payload) }
        assertEquals(crc.value.toInt(), AdbProtocol.crc32(payload))
    }

    @Test
    fun decodeHeaderRejectsBadMagic() {
        val frame = AdbProtocol.encode(AdbProtocol.CNXN, 1, 2, ByteArray(0))
        frame[20] = 0 // corrupt the magic word
        assertThrows(IllegalArgumentException::class.java) {
            AdbProtocol.decodeHeader(frame)
        }
    }

    @Test
    fun decodeRejectsTruncatedPayload() {
        val frame = AdbProtocol.encode(AdbProtocol.WRTE, 1, 1, "abcdef".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            AdbProtocol.decode(frame.copyOfRange(0, AdbProtocol.HEADER_LENGTH + 3))
        }
    }

    @Test
    fun decodeHeaderRejectsOversizedPayload() {
        val frame = AdbProtocol.encode(AdbProtocol.WRTE, 1, 1, ByteArray(0))
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(12, AdbProtocol.MAX_DATA + 1) // overwrite the length field
        assertThrows(IllegalArgumentException::class.java) {
            AdbProtocol.decodeHeader(frame)
        }
    }

    @Test
    fun messageEqualityComparesPayloadContent() {
        val a = AdbProtocol.Message(AdbProtocol.WRTE, 1, 2, byteArrayOf(1, 2, 3))
        val b = AdbProtocol.Message(AdbProtocol.WRTE, 1, 2, byteArrayOf(1, 2, 3))
        val c = AdbProtocol.Message(AdbProtocol.WRTE, 1, 2, byteArrayOf(1, 2, 4))
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
