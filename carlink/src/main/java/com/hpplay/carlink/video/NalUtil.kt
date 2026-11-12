package com.hpplay.carlink.video

/**
 * H.264 / H.265 参数集提取与 Annex-B 归一化。
 *
 * libovmsink.so 回传的是"编码后的 access unit"。蒲公英的 VideoPlayerV2 不设 csd-0，直接把带内
 * SPS/PPS 喂给 MediaCodec，说明参数集在码流里（Annex-B）。Hplay 的 AndroidMediaSink 需要显式
 * csd，因此这里从首批帧里抽取参数集构造 csd，并把每帧归一化为 Annex-B 再喂给解码器。
 *
 * 同时兼容两种封装：Annex-B（00 00 00 01 / 00 00 01）与 4 字节长度前缀（AVCC）。
 */
object NalUtil {
    private val START4 = byteArrayOf(0, 0, 0, 1)
    private val START3 = byteArrayOf(0, 0, 1)

    /** 是否为 Annex-B（起始码）。 */
    fun isAnnexB(data: ByteArray): Boolean =
        (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1) ||
            (data.size >= 3 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1)

    /**
     * 把一帧归一化为 Annex-B。Annex-B 原样返回；4 字节长度前缀则转成 Annex-B。
     */
    fun toAnnexB(data: ByteArray): ByteArray {
        if (isAnnexB(data)) return data
        val out = java.io.ByteArrayOutputStream()
        var c = 0
        while (c + 4 <= data.size) {
            var len = 0
            for (i in 0 until 4) len = (len shl 8) or (data[c + i].toInt() and 0xFF)
            c += 4
            if (len <= 0 || c + len > data.size) return data // 不是合法 AVCC，原样返回
            out.write(START4)
            out.write(data, c, len)
            c += len
        }
        return if (out.size() == 0) data else out.toByteArray()
    }

    /** 拆分 Annex-B 帧为 NAL 单元（不含起始码）。 */
    fun splitNalUnits(annexB: ByteArray): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var i = 0
        val starts = mutableListOf<Int>()
        while (i < annexB.size) {
            val sc = startCodeLen(annexB, i)
            if (sc > 0) {
                starts.add(i + sc)
                i += sc
            } else i++
        }
        for (k in starts.indices) {
            val from = starts[k]
            val to = if (k + 1 < starts.size) {
                // 回退到下一个起始码前（去掉 3/4 字节起始码）
                var end = starts[k + 1]
                // starts 记录的是起始码之后的位置，需要往前找起始码本身
                var p = end - 1
                // 找该位置的起始码长度
                // 简化：向前探测 4/3 字节起始码
                when {
                    end - 4 >= from && annexB[end - 4].toInt() == 0 && annexB[end - 3].toInt() == 0 && annexB[end - 2].toInt() == 0 && annexB[end - 1].toInt() == 1 -> end - 4
                    end - 3 >= from && annexB[end - 3].toInt() == 0 && annexB[end - 2].toInt() == 0 && annexB[end - 1].toInt() == 1 -> end - 3
                    else -> p
                }
            } else annexB.size
            if (to > from) units.add(annexB.copyOfRange(from, to))
        }
        return units
    }

    private fun startCodeLen(b: ByteArray, i: Int): Int = when {
        i + 4 <= b.size && b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1 -> 4
        i + 3 <= b.size && b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1 -> 3
        else -> 0
    }

    private fun nalTypeH264(nal: ByteArray): Int = nal[0].toInt() and 0x1F
    private fun nalTypeH265(nal: ByteArray): Int = (nal[0].toInt() ushr 1) and 0x3F

    /**
     * 从一批帧里抽取参数集，构造 Annex-B 形式的 csd。
     * @param isHevc true=H265，false=H264
     * @return csd 或 null（尚未见到参数集）
     */
    fun extractCsd(frames: List<ByteArray>, isHevc: Boolean): ByteArray? {
        val wanted = if (isHevc) setOf(32, 33, 34) else setOf(7, 8)
        val found = LinkedHashMap<Int, ByteArray>()
        for (frame in frames) {
            val annexB = toAnnexB(frame)
            if (!isAnnexB(annexB)) continue
            for (nal in splitNalUnits(annexB)) {
                if (nal.isEmpty()) continue
                val type = if (isHevc) nalTypeH265(nal) else nalTypeH264(nal)
                if (type in wanted && !found.containsKey(type)) found[type] = nal
            }
            if (found.keys.containsAll(wanted)) break
        }
        if (!found.keys.containsAll(wanted)) return null
        val out = java.io.ByteArrayOutputStream()
        for (type in wanted) { // 32/33/34 或 7/8，顺序固定
            val nal = found[type] ?: continue
            out.write(START4)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** 帧里是否含 IDR/随机接入点（H264 type5 / H265 type16..21）。 */
    fun containsKeyFrame(frame: ByteArray, isHevc: Boolean): Boolean {
        val annexB = toAnnexB(frame)
        if (!isAnnexB(annexB)) return false
        for (nal in splitNalUnits(annexB)) {
            if (nal.isEmpty()) continue
            if (isHevc) {
                val t = nalTypeH265(nal)
                if (t in 16..21) return true
            } else {
                if (nalTypeH264(nal) == 5) return true
            }
        }
        return false
    }
}
