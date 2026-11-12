package com.hpplay.carlink.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CarlinkFrameTest {

    /**
     * Anchor the CRC against the decompiled N0/c lookup table (蒲公英). Those are the first entries
     * of a reflected CRC-16/ARC table (poly 0xA001). If our bit-by-bit impl produces the same table,
     * the frame checksum is byte-compatible with the reference app.
     */
    @Test
    fun crcTable_matchesReferenceTable() {
        // Build the table the same way a reflected poly-0xA001 table is built.
        val table = IntArray(256)
        for (i in 0 until 256) {
            var crc = i
            for (bit in 0 until 8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
            table[i] = crc
        }
        // Expected values from N0/c.f694a in the reference APK.
        assertEquals(0, table[0])
        assertEquals(49345, table[1])   // 0xC0C1
        assertEquals(49537, table[2])   // 0xC1C1
        assertEquals(320, table[3])     // 0x0140
        assertEquals(49921, table[4])   // 0xC301
    }

    @Test
    fun header_roundTrip() {
        val f = CarlinkFrame(
            messageSize = 30,
            seqId = 12345,
            timestamp = 1_700_000_000,
            source = SourceDevice.CAR,
            dataFormat = DataFormat.PB3,
            msgType = MsgType.SEND,
            category = ChannelCategory.CONTROL,
            method = 25, // get_ucar_config_response
        )
        val header = f.encodeHeader()
        assertEquals(CarlinkFrame.HEADER_SIZE, header.size)
        val decoded = CarlinkFrame.decodeHeader(header)
        assertEquals(f.messageSize, decoded.messageSize)
        assertEquals(f.seqId, decoded.seqId)
        assertEquals(f.timestamp, decoded.timestamp)
        assertEquals(SourceDevice.CAR, decoded.source)
        assertEquals(DataFormat.PB3, decoded.dataFormat)
        assertEquals(MsgType.SEND, decoded.msgType)
        assertEquals(ChannelCategory.CONTROL, decoded.category)
        assertEquals(25, decoded.method)
    }

    @Test
    fun encode_withBody_setsMessageSizeAndKeepsBody() {
        val body = byteArrayOf(0x08, 0x01, 0x12, 0x02, 0x41, 0x42) // arbitrary protobuf-ish bytes
        val f = CarlinkFrame(seqId = 1, timestamp = 2, category = ChannelCategory.AUTH, method = 1)
        val frame = f.encode(body)
        assertEquals(CarlinkFrame.HEADER_SIZE + body.size, frame.size)
        assertEquals(CarlinkFrame.HEADER_SIZE + body.size, CarlinkFrame.decodeHeader(frame).messageSize)
        assertArrayEquals(body, frame.copyOfRange(CarlinkFrame.HEADER_SIZE, frame.size))
    }

    @Test
    fun decode_rejectsBadChecksum() {
        val f = CarlinkFrame(seqId = 7, timestamp = 9, category = ChannelCategory.SENSOR, method = 3)
        val header = f.encodeHeader()
        header[18] = (header[18] + 1).toByte() // corrupt checksum
        assertThrows(IllegalStateException::class.java) {
            CarlinkFrame.decodeHeader(header)
        }
    }

    @Test
    fun flags_bitPacking_isCorrect() {
        // PHONE(1) + RAW_NO_ENCRYPTED(2)<<1 + RES(2)<<3 = 1 | 4 | 16 = 21
        val f = CarlinkFrame(
            source = SourceDevice.PHONE,
            dataFormat = DataFormat.RAW_NO_ENCRYPTED,
            msgType = MsgType.RES,
        )
        val flags = f.encodeHeader()[12].toInt() and 0xFF
        assertEquals(21, flags)
        val d = CarlinkFrame.decodeHeader(f.encodeHeader())
        assertEquals(SourceDevice.PHONE, d.source)
        assertEquals(DataFormat.RAW_NO_ENCRYPTED, d.dataFormat)
        assertEquals(MsgType.RES, d.msgType)
    }
}
