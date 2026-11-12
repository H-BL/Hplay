package com.hpplay.carlink.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NalUtilTest {
    private val sc = byteArrayOf(0, 0, 0, 1)

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (n in nals) { out.write(sc); out.write(n) }
        return out.toByteArray()
    }

    @Test
    fun isAnnexB_detectsStartCode() {
        assertTrue(NalUtil.isAnnexB(sc + byteArrayOf(0x67)))
        assertTrue(NalUtil.isAnnexB(byteArrayOf(0, 0, 1, 0x67)))
        assertTrue(!NalUtil.isAnnexB(byteArrayOf(0, 0, 0, 9)))
    }

    @Test
    fun toAnnexB_passesThroughAnnexB() {
        val f = annexB(byteArrayOf(0x65, 1, 2, 3))
        assertArrayEquals(f, NalUtil.toAnnexB(f))
    }

    @Test
    fun toAnnexB_convertsLengthPrefixed() {
        // one NAL of len 3
        val avcc = byteArrayOf(0, 0, 0, 3, 0x65, 1, 2)
        val out = NalUtil.toAnnexB(avcc)
        assertArrayEquals(sc + byteArrayOf(0x65, 1, 2), out)
    }

    @Test
    fun splitNalUnits_splits() {
        val f = annexB(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2), byteArrayOf(0x65, 3))
        val units = NalUtil.splitNalUnits(f)
        assertEquals(3, units.size)
        assertArrayEquals(byteArrayOf(0x67, 1), units[0])
        assertArrayEquals(byteArrayOf(0x65, 3), units[2])
    }

    @Test
    fun extractCsd_h264_findsSpsPps() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())
        val frames = listOf(annexB(sps, pps, idr))
        val csd = NalUtil.extractCsd(frames, isHevc = false)
        assertNotNull(csd)
        // csd should be START + sps + START + pps
        assertArrayEquals(annexB(sps, pps), csd)
    }

    @Test
    fun extractCsd_h264_returnsNullWithoutPps() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val frames = listOf(annexB(sps))
        assertNull(NalUtil.extractCsd(frames, isHevc = false))
    }

    @Test
    fun extractCsd_hevc_findsVpsSpsPps() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC0.toByte())
        val frames = listOf(annexB(vps, sps, pps))
        val csd = NalUtil.extractCsd(frames, isHevc = true)
        assertNotNull(csd)
        assertArrayEquals(annexB(vps, sps, pps), csd)
    }

    @Test
    fun containsKeyFrame_h264() {
        assertTrue(NalUtil.containsKeyFrame(annexB(byteArrayOf(0x65, 0)), false))
        assertTrue(!NalUtil.containsKeyFrame(annexB(byteArrayOf(0x41, 0)), false))
    }

    @Test
    fun containsKeyFrame_hevc() {
        // type 19 = IDR_W_RADL -> (0x26 >> 1)&0x3f = 19
        assertTrue(NalUtil.containsKeyFrame(annexB(byteArrayOf(0x26, 0x01)), true))
        // type 1 (TRAIL_R) = 0x02>>1 = 1
        assertTrue(!NalUtil.containsKeyFrame(annexB(byteArrayOf(0x02, 0x01)), true))
    }
}
