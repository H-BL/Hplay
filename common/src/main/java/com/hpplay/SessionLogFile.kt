package com.hpplay

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class SessionLogFile(val file: File, private val appendMode: Boolean = false) : Closeable {
    private val lock = Any()
    private val writerExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_LINES),
        { task -> Thread(task, "hplay-log-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private var output: BufferedOutputStream? = null
    private var bytesWritten = 0L
    private var closed = false
    private val lineFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun reset(header: String) {
        synchronized(lock) {
            if (closed) return
            file.parentFile?.mkdirs()
            output?.close()
            val existing = if (appendMode && file.isFile) file.length() else 0L
            output = file.outputStream().buffered()
            bytesWritten = 0L
            if (appendMode && existing > 0 && file.isFile) {
                // Keep whatever earlier this session already wrote; only add the header.
                output = java.io.FileOutputStream(file, true).buffered()
                bytesWritten = existing
            }
            writeLine(header)
        }
    }

    fun append(line: String) = enqueue { line }

    fun appendTimestamped(message: String, timestampMillis: Long) = enqueue {
        "${lineFormatter.format(Date(timestampMillis))}  $message"
    }

    private fun enqueue(line: () -> String) {
        synchronized(lock) {
            if (closed) return
            writerExecutor.execute {
                try {
                    writeLine(line())
                } catch (_: IOException) {
                    runCatching { output?.close() }
                    output = null
                }
            }
        }
    }

    private fun writeLine(line: String) {
        var bytes = line.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size >= MAX_BYTES) {
            var start = bytes.size - (MAX_BYTES - 1)
            while (start < bytes.size && bytes[start].toInt() and 0xc0 == 0x80) start++
            bytes = bytes.copyOfRange(start, bytes.size)
        }
        if (bytesWritten + bytes.size + 1 > MAX_BYTES) {
            output?.close()
            output = file.outputStream().buffered()
            bytesWritten = 0L
        }
        val activeOutput = output ?: return
        activeOutput.write(bytes)
        activeOutput.write('\n'.code)
        activeOutput.flush()
        bytesWritten += bytes.size + 1
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            writerExecutor.execute {
                runCatching { output?.close() }
                output = null
            }
            writerExecutor.shutdown()
        }
        writerExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    private companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        const val MAX_PENDING_LINES = 1024
    }
}
