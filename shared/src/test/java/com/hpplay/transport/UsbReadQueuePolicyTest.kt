package com.hpplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * USB 读入队降级策略（移植自参考实现 DiPlay 的 UsbReadQueuePolicy）。
 *
 * 背景：Hplay 原先在 `UsbRequest.queue()` 返回 false 时直接判定会话失败，日志表现为
 * `Android could not queue USBMUX read request`，随后有线 CarPlay 整轮 bring-up 失败。
 * 参考实现在失败时按 16384 → 8192 → 4096 → 2048 逐级降级重试，并记住成功尺寸。
 */
class UsbReadQueuePolicyTest {

    private fun bufferOf(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size)

    @Test
    fun `first attempt succeeds at full size`() {
        val policy = UsbReadQueuePolicy()
        val buffer = bufferOf(65_536)
        var calls = 0
        val result = policy.queue(buffer, { }) { calls++; true }

        assertTrue(result.queued)
        assertEquals(65_536, result.firstBytes)
        assertNull(result.fallbackBytes)
        assertEquals(1, calls)
    }

    @Test
    fun `falls back to a smaller size and reports it`() {
        val policy = UsbReadQueuePolicy()
        val buffer = bufferOf(65_536)
        var calls = 0
        // 只接受 <= 8192 的提交，模拟车机 USB 栈拒绝大尺寸 URB
        val result = policy.queue(buffer, { }) { b -> calls++; b.remaining() <= 8192 }

        assertTrue("降级后必须成功", result.queued)
        assertEquals(65_536, result.firstBytes)
        assertEquals(8192, result.fallbackBytes)
        assertTrue("应当重试多次", calls > 1)
    }

    @Test
    fun `remembers the accepted size for the next read`() {
        val policy = UsbReadQueuePolicy()
        // 第一次：降级到 8192
        policy.queue(bufferOf(65_536), { }) { b -> b.remaining() <= 8192 }
        // 第二次：应当直接用 8192 作为首次尝试，一次命中
        var calls = 0
        val second = policy.queue(bufferOf(65_536), { }) { b -> calls++; b.remaining() <= 8192 }

        assertTrue(second.queued)
        assertEquals("第二次应直接使用记住的尺寸", 8192, second.firstBytes)
        assertEquals("应当一次命中", 1, calls)
    }

    @Test
    fun `fails when every size is rejected`() {
        val policy = UsbReadQueuePolicy()
        val result = policy.queue(bufferOf(65_536), { }) { false }

        assertFalse(result.queued)
        assertNotNull("失败时要报告最后尝试的尺寸", result.fallbackBytes)
    }

    @Test
    fun `short regions are never retried`() {
        val policy = UsbReadQueuePolicy()
        var calls = 0
        val result = policy.queue(bufferOf(1024), { }) { calls++; false }

        assertFalse(result.queued)
        assertEquals("小于 2KiB 的区域不做降级重试", 1, calls)
    }

    @Test
    fun `rejected queue that mutates the buffer throws`() {
        val policy = UsbReadQueuePolicy()
        val buffer = bufferOf(65_536)
        var thrown: Throwable? = null
        try {
            policy.queue(buffer, { }) { b ->
                b.position(b.position() + 100) // 违反契约：失败却改动了 buffer
                false
            }
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue("buffer 状态被改动时必须报错", thrown is IllegalStateException)
    }

    @Test
    fun `open check aborts before queueing`() {
        val policy = UsbReadQueuePolicy()
        var calls = 0
        var thrown: Throwable? = null
        try {
            policy.queue(bufferOf(65_536), { throw IllegalStateException("closed") }) { calls++; true }
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue(thrown is IllegalStateException)
        assertEquals("未打开时不应提交", 0, calls)
    }

    @Test
    fun `platform ceiling is only applied on api 26 and 27`() {
        // Android 9 (28) 的 queue(ByteBuffer) 接受任意尺寸，因此不设上限
        val policy = UsbReadQueuePolicy()
        val result = policy.queue(bufferOf(65_536), { }) { true }
        assertEquals("Android 9 上首次尝试即为全尺寸", 65_536, result.firstBytes)
    }
}
