package com.hpplay.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Wire format of the ADB smart-socket protocol, host side.
 *
 * Only the message framing lives here so it can be exercised without a socket: [encode] builds the
 * 24-byte header plus payload, [decodeHeader] validates the magic word, and [decode] reads a whole
 * message back out of a buffer (used by the unit tests). The framing is deliberately independent of
 * Android so the client and its tests share one definition.
 *
 * The F510C head unit starts adbd on TCP port 5557 as `uid 0` and does not ask hosts to
 * authenticate, so a plain loopback connection plus an `OPEN("shell:…")` is a root shell. This
 * protocol is how the "force 5 GHz hotspot" flow reaches root without Magisk.
 */
internal object AdbProtocol {
    /** `A_VERSION`, the host/device protocol version sent in `CNXN`. */
    const val VERSION = 0x01000001

    /** Largest payload a peer may send; adbd on the head unit advertises exactly this. */
    const val MAX_DATA = 1024 * 1024

    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257
    const val STLS = 0x534c5453

    /** Fixed header size of every ADB message. */
    const val HEADER_LENGTH = 24

    /** Banner a host sends in its opening `CNXN`; the trailing NUL is part of the wire format. */
    val HOST_BANNER: ByteArray = "host::\u0000".toByteArray(Charsets.UTF_8)

    /** Decoded 24-byte ADB header. */
    data class Header(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val length: Int,
        val checksum: Int,
    )

    /** A full ADB message: header fields plus its payload. */
    data class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    ) {
        fun payloadText(): String = String(payload, Charsets.UTF_8)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Message) return false
            return command == other.command &&
                arg0 == other.arg0 &&
                arg1 == other.arg1 &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = command
            result = 31 * result + arg0
            result = 31 * result + arg1
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /** The `OPEN` service string that asks adbd to run [command] in its shell. */
    fun shellService(command: String): ByteArray =
        "shell:$command\u0000".toByteArray(Charsets.UTF_8)

    /** CRC32 of [payload], as carried in the header. */
    fun crc32(payload: ByteArray): Int {
        val crc = CRC32()
        crc.update(payload)
        return crc.value.toInt()
    }

    /** Encodes one ADB message (header + payload) into a single byte array. */
    fun encode(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val buffer = ByteBuffer
            .allocate(HEADER_LENGTH + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(payload.size)
        buffer.putInt(crc32(payload))
        buffer.putInt(command.inv())
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Decodes and validates a 24-byte header.
     *
     * @throws IllegalArgumentException when the buffer is not a header or the magic word is wrong.
     */
    fun decodeHeader(bytes: ByteArray): Header {
        require(bytes.size == HEADER_LENGTH) { "ADB header must be $HEADER_LENGTH bytes" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        require(magic == command.inv()) {
            "bad ADB magic 0x${Integer.toHexString(magic)} for command 0x${Integer.toHexString(command)}"
        }
        require(length in 0..MAX_DATA) { "ADB payload length out of range: $length" }
        return Header(command, arg0, arg1, length, checksum)
    }

    /**
     * Decodes a complete message from [bytes].
     *
     * @throws IllegalArgumentException when the framing is malformed or the payload is truncated.
     */
    fun decode(bytes: ByteArray): Message {
        val header = decodeHeader(bytes.copyOf(HEADER_LENGTH))
        require(bytes.size >= HEADER_LENGTH + header.length) { "ADB payload is truncated" }
        val payload = bytes.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + header.length)
        return Message(header.command, header.arg0, header.arg1, payload)
    }
}
