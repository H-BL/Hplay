package com.hpplay.carlife.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeFrameTest {

    @Test
    fun `cmd short frame roundtrip`() {
        val frame = CarLifeFrame(CarLife.CHANNEL_CMD, CarLife.MSG_CMD_HU_INFO, byteArrayOf(1, 2, 3))
        val wire = CarLifeFrameCodec.encode(frame)
        assertEquals(8 + 3, wire.size)
        // payloadSize u16 BE @0
        assertEquals(3, ((wire[0].toInt() and 255) shl 8) or (wire[1].toInt() and 255))
        // serviceType u32 BE @4
        assertEquals(CarLife.MSG_CMD_HU_INFO, CarLifeFrameCodec.readInt(wire, 4))
        val back = CarLifeFrameCodec.decode(CarLife.CHANNEL_CMD, wire)
        assertEquals(frame, back)
    }

    @Test
    fun `video long frame roundtrip with timestamp`() {
        val payload = ByteArray(60000) { (it % 251).toByte() }
        val frame = CarLifeFrame(CarLife.CHANNEL_VIDEO, CarLife.MSG_VIDEO_DATA, payload, timestamp = 0x11223344)
        val wire = CarLifeFrameCodec.encode(frame)
        assertEquals(12 + payload.size, wire.size)
        assertEquals(0x11223344, CarLifeFrameCodec.readInt(wire, 4))
        assertEquals(CarLife.MSG_VIDEO_DATA, CarLifeFrameCodec.readInt(wire, 8))
        val back = CarLifeFrameCodec.decode(CarLife.CHANNEL_VIDEO, wire)
        assertEquals(frame, back)
        assertArrayEquals(payload, back.payload)
    }

    @Test
    fun `touch uses short command`() {
        assertEquals(8, CarLifeFrameCodec.commandSize(CarLife.CHANNEL_TOUCH))
        assertEquals(12, CarLifeFrameCodec.commandSize(CarLife.CHANNEL_VIDEO))
        assertEquals(12, CarLifeFrameCodec.commandSize(CarLife.CHANNEL_AUDIO))
    }

    @Test
    fun `stream assembler reassembles split frames`() {
        val assembler = CarLifeFrameCodec.StreamAssembler(CarLife.CHANNEL_CMD)
        val f1 = CarLifeFrame(CarLife.CHANNEL_CMD, 0x100, byteArrayOf(9, 8, 7, 6))
        val f2 = CarLifeFrame(CarLife.CHANNEL_CMD, 0x200, byteArrayOf(5))
        val wire = CarLifeFrameCodec.encode(f1) + CarLifeFrameCodec.encode(f2)

        // 逐字节喂入，最后应吐出两帧
        val frames = mutableListOf<CarLifeFrame>()
        for (i in wire.indices) {
            frames += assembler.feed(wire, i, 1)
        }
        assertEquals(2, frames.size)
        assertEquals(f1, frames[0])
        assertEquals(f2, frames[1])
    }

    @Test
    fun `stream assembler drains multiple frames in one batch`() {
        // 回归：同一段 TCP 读里含两帧时必须全部吐出（集成测试抓出的丢帧 bug）
        val assembler = CarLifeFrameCodec.StreamAssembler(CarLife.CHANNEL_CMD)
        val f1 = CarLifeFrame(CarLife.CHANNEL_CMD, 0x300, byteArrayOf(1, 2))
        val f2 = CarLifeFrame(CarLife.CHANNEL_CMD, 0x400, byteArrayOf(3))
        val wire = CarLifeFrameCodec.encode(f1) + CarLifeFrameCodec.encode(f2)

        val frames = assembler.feed(wire, 0, wire.size)
        assertEquals(2, frames.size)
        assertEquals(f1, frames[0])
        assertEquals(f2, frames[1])
        // 之后再来一帧仍能正常解析（状态已复位）
        val f3 = CarLifeFrame(CarLife.CHANNEL_CMD, 0x500, byteArrayOf(4, 5, 6))
        assertEquals(listOf(f3), assembler.feed(CarLifeFrameCodec.encode(f3), 0, 11))
    }

    @Test
    fun `stream assembler drains trailing empty-payload frames`() {
        // 回归：空载荷帧（PAUSE/START）背靠背且位于批次末尾时不能滞留
        val assembler = CarLifeFrameCodec.StreamAssembler(CarLife.CHANNEL_CMD)
        val pause = CarLifeFrame(CarLife.CHANNEL_CMD, CarLife.MSG_CMD_VIDEO_ENCODER_PAUSE, ByteArray(0))
        val start = CarLifeFrame(CarLife.CHANNEL_CMD, CarLife.MSG_CMD_VIDEO_ENCODER_START, ByteArray(0))
        val wire = CarLifeFrameCodec.encode(pause) + CarLifeFrameCodec.encode(start)

        val frames = assembler.feed(wire, 0, wire.size)
        assertEquals(listOf(pause, start), frames)
        // 分段到达：前帧带载荷 + 尾帧空载荷，一次 feed 内全部吐出
        val withData = CarLifeFrame(CarLife.CHANNEL_CMD, 0x600, byteArrayOf(7, 8, 9))
        val wire2 = CarLifeFrameCodec.encode(withData) + CarLifeFrameCodec.encode(pause)
        val frames2 = CarLifeFrameCodec.StreamAssembler(CarLife.CHANNEL_CMD).feed(wire2, 0, wire2.size)
        assertEquals(listOf(withData, pause), frames2)
    }

    @Test
    fun `stream assembler returns empty until complete`() {
        val assembler = CarLifeFrameCodec.StreamAssembler(CarLife.CHANNEL_VIDEO)
        val payload = ByteArray(300) { it.toByte() }
        val wire = CarLifeFrameCodec.encode(
            CarLifeFrame(CarLife.CHANNEL_VIDEO, CarLife.MSG_VIDEO_DATA, payload),
        )
        // 前 5 字节 + 后面的数据分两段喂
        assertTrue(assembler.feed(wire, 0, 5).isEmpty())
        val frames = assembler.feed(wire, 5, wire.size - 5)
        assertEquals(1, frames.size)
        assertEquals(CarLife.MSG_VIDEO_DATA, frames[0].serviceType)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test
    fun `stream assembler handles big-endian length`() {
        val assembler = CarLifeFrameCodec.StreamAssembler(CarLife.CHANNEL_VIDEO)
        val payload = ByteArray(300) { it.toByte() }
        val wire = CarLifeFrameCodec.encode(
            CarLifeFrame(CarLife.CHANNEL_VIDEO, CarLife.MSG_VIDEO_DATA, payload),
        )
        // 前 5 字节 + 后面的数据分两段喂
        assertTrue(assembler.feed(wire, 0, 5).isEmpty())
        val frames = assembler.feed(wire, 5, wire.size - 5)
        assertEquals(1, frames.size)
        assertEquals(CarLife.MSG_VIDEO_DATA, frames[0].serviceType)
        assertArrayEquals(payload, frames[0].payload)
    }
}
