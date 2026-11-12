package com.hpplay.carlife.proto

/**
 * 极简 protobuf 运行时：只覆盖 CarLife 用到的 varint / 长度界定（string/bytes/message），
 * 字段号以引擎 APK 自带的 75 个 .proto 为准。无外部依赖，纯 JVM 可测。
 */
object ProtoIO {

    fun writeVarint(out: ByteArray, pos: Int, value: Long): Int {
        var v = value
        var p = pos
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out[p++] = v.toByte()
                return p
            }
            out[p++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
    }

    fun varintSize(value: Long): Int {
        var v = value
        var n = 1
        while (v and 0x7FL.inv() != 0L) {
            v = v ushr 7
            n++
        }
        return n
    }

    fun readVarint(data: ByteArray, pos: Int, limit: Int): Pair<Long, Int> {
        var v = 0L
        var shift = 0
        var p = pos
        while (p < limit) {
            val b = data[p++].toInt() and 255
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return v to p
            shift += 7
            if (shift > 63) break
        }
        throw IllegalArgumentException("malformed varint at $pos")
    }

    // ---- Writer ----
    class Writer {
        private var buf = ByteArray(64)
        private var len = 0

        private fun ensure(extra: Int) {
            if (len + extra > buf.size) {
                buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
            }
        }

        fun varint(field: Int, value: Long) {
            val key = (field.toLong() shl 3) or 0
            ensure(varintSize(key) + varintSize(value))
            len = writeVarint(buf, len, key)
            len = writeVarint(buf, len, value)
        }

        fun int32(field: Int, value: Int) = varint(field, value.toLong())

        fun bool(field: Int, value: Boolean) = varint(field, if (value) 1L else 0L)

        fun bytes(field: Int, value: ByteArray) {
            val key = (field.toLong() shl 3) or 2
            ensure(varintSize(key) + varintSize(value.size.toLong()) + value.size)
            len = writeVarint(buf, len, key)
            len = writeVarint(buf, len, value.size.toLong())
            System.arraycopy(value, 0, buf, len, value.size)
            len += value.size
        }

        fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun message(field: Int, block: Writer.() -> Unit) = bytes(field, Writer().apply(block).toByteArray())

        fun toByteArray(): ByteArray = buf.copyOf(len)

        val size: Int get() = len
    }

    // ---- Reader ----
    class Reader(private val data: ByteArray, private val end: Int = data.size) {
        private var pos = 0

        val isAtEnd: Boolean get() = pos >= end

        fun nextField(): Pair<Int, Int> {
            val (key, next) = readVarint(data, pos, end)
            pos = next
            return (key ushr 3).toInt() to (key and 0x7).toInt()
        }

        fun varint(): Long {
            val (v, next) = readVarint(data, pos, end)
            pos = next
            return v
        }

        fun bytes(): ByteArray {
            val (len, next) = readVarint(data, pos, end)
            pos = next
            val l = len.toInt()
            require(l >= 0 && pos + l <= end) { "truncated length-delimited field" }
            val out = data.copyOfRange(pos, pos + l)
            pos += l
            return out
        }

        fun string(): String = String(bytes(), Charsets.UTF_8)

        fun nested(): Reader {
            val b = bytes()
            return Reader(b, b.size)
        }

        /** 跳过一个字段值（wire type 0/2 足够 CarLife 使用；5/1 按固定长度跳）。 */
        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> throw IllegalArgumentException("unsupported wire type $wireType")
            }
        }
    }
}
