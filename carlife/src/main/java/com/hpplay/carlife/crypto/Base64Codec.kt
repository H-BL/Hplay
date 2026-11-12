package com.hpplay.carlife.crypto

/**
 * RFC 4648 Base64（标准字母表，编码不带换行）。minSdk 24 下 java.util.Base64 不可用
 * （API 26+），android.util.Base64 在 JVM 单测里是 stub，因此自带实现，纯 JVM 可测。
 */
internal object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 255
            val has1 = i + 1 < data.size
            val has2 = i + 2 < data.size
            val b1 = if (has1) data[i + 1].toInt() and 255 else 0
            val b2 = if (has2) data[i + 2].toInt() and 255 else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[(b0 and 3 shl 4) or (b1 shr 4)])
            sb.append(if (has1) ALPHABET[(b1 and 15 shl 2) or (b2 shr 6)] else '=')
            sb.append(if (has2) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val trimmed = text.trim().replace("\n", "").replace("\r", "")
        val out = ByteArray(trimmed.length * 3 / 4)
        var outLen = 0
        var buffer = 0
        var bits = 0
        for (c in trimmed) {
            if (c == '=') break
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> throw IllegalArgumentException("invalid base64 char: $c")
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outLen++] = (buffer shr bits and 255).toByte()
            }
        }
        return out.copyOf(outLen)
    }
}
