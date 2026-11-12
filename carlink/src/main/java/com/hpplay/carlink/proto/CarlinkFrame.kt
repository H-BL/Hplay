package com.hpplay.carlink.proto

/**
 * ICCOA CarLink 帧头编解码（20 字节，全大端），以及帧校验。
 *
 * 布局（源自蒲公英 N0/g + N0/i，已逐字段核对）：
 * ```
 *  0..4   messageSize  uint32  整帧长度（含 20B 头）
 *  4..8   seqId        uint32  序列号
 *  8..12  timestamp    uint32  秒级时间戳
 *  12     flags        uint8   bit0 source | bit1-2 dataFormat | bit3-4 msgType
 *  13     category     uint8   ChannelCategory
 *  14..16 method       uint16  通道内消息号
 *  16..18 reserve      uint16  保留 0
 *  18..20 checksum     uint16  CRC-16/ARC（反射式，poly 0xA001，init 0），对前 18 字节
 * ```
 */
class CarlinkFrame(
    var messageSize: Int = 20,
    var seqId: Int = 0,
    var timestamp: Int = 0,
    var source: SourceDevice = SourceDevice.CAR,
    var dataFormat: DataFormat = DataFormat.RAW,
    var msgType: MsgType = MsgType.SEND,
    var category: ChannelCategory = ChannelCategory.NONE,
    var method: Int = 0,
) {
    /** 序列化为 20 字节头（不包含 body）。 */
    fun encodeHeader(): ByteArray {
        val b = ByteArray(HEADER_SIZE)
        putInt(b, 0, messageSize)
        putInt(b, 4, seqId)
        putInt(b, 8, timestamp)
        var flags = (source.value and 0x01)
        flags = flags or ((dataFormat.value and 0x03) shl 1)
        flags = flags or ((msgType.value and 0x03) shl 3)
        b[12] = flags.toByte()
        b[13] = category.value.toByte()
        b[14] = ((method ushr 8) and 0xFF).toByte()
        b[15] = (method and 0xFF).toByte()
        b[16] = 0
        b[17] = 0
        val crc = crc16Arc(b, 0, 18)
        b[18] = ((crc ushr 8) and 0xFF).toByte()
        b[19] = (crc and 0xFF).toByte()
        return b
    }

    /** 编码整帧：20B 头（messageSize 自动 = 20 + body.size）+ body。 */
    fun encode(body: ByteArray): ByteArray {
        messageSize = HEADER_SIZE + body.size
        val out = ByteArray(messageSize)
        System.arraycopy(encodeHeader(), 0, out, 0, HEADER_SIZE)
        System.arraycopy(body, 0, out, HEADER_SIZE, body.size)
        return out
    }

    override fun toString(): String =
        "CarlinkFrame(size=$messageSize, seq=$seqId, ts=$timestamp, src=$source, " +
            "fmt=$dataFormat, type=$msgType, cat=$category, method=$method)"

    companion object {
        const val HEADER_SIZE = 20

        /** 从 20 字节头解析。会校验 checksum。 */
        fun decodeHeader(b: ByteArray, offset: Int = 0): CarlinkFrame {
            require(b.size - offset >= HEADER_SIZE) { "header bytes < 20" }
            val size = getInt(b, offset)
            val seq = getInt(b, offset + 4)
            val ts = getInt(b, offset + 8)
            val flags = b[offset + 12].toInt() and 0xFF
            val source = SourceDevice.from(flags and 0x01)
            val dataFormat = DataFormat.from((flags ushr 1) and 0x03)
            val msgType = MsgType.from((flags ushr 3) and 0x03)
            val category = ChannelCategory.from(b[offset + 13].toInt() and 0xFF)
            val method = ((b[offset + 14].toInt() and 0xFF) shl 8) or (b[offset + 15].toInt() and 0xFF)
            val expected = ((b[offset + 18].toInt() and 0xFF) shl 8) or (b[offset + 19].toInt() and 0xFF)
            val actual = crc16Arc(b, offset, offset + 18)
            check(expected == actual) {
                "bad checksum expect=0x${expected.toString(16)} actual=0x${actual.toString(16)}"
            }
            return CarlinkFrame(size, seq, ts, source, dataFormat, msgType, category, method)
        }

        /** CRC-16/ARC（反射式，poly 0xA001，init 0，无 final xor）。等价于蒲公英 N0/c 的查表实现。 */
        fun crc16Arc(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
            var crc = 0
            for (i in from until to) {
                crc = crc xor (data[i].toInt() and 0xFF)
                for (bit in 0 until 8) {
                    crc = if ((crc and 0x0001) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
                }
            }
            return crc and 0xFFFF
        }

        private fun putInt(b: ByteArray, off: Int, v: Int) {
            b[off] = ((v ushr 24) and 0xFF).toByte()
            b[off + 1] = ((v ushr 16) and 0xFF).toByte()
            b[off + 2] = ((v ushr 8) and 0xFF).toByte()
            b[off + 3] = (v and 0xFF).toByte()
        }

        private fun getInt(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)
    }
}
