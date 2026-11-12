package com.hpplay.media

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import com.hpplay.airplay.AudioCodecKind
import com.hpplay.airplay.MicrophoneConfig
import com.hpplay.airplay.MicrophoneCounters
import com.hpplay.airplay.MicrophonePacketizer
import com.hpplay.airplay.microphoneBindAddress
import com.hpplay.airplay.toHexString
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures 16 kHz mono PCM from Android and sends it in the format negotiated by CarPlay.
 *
 * Wired sessions use PCM; wireless sessions use Opus. Both share the same recorder and socket
 * setup so the device-specific audio mode and address-family requirements stay consistent.
 */
internal class MicrophoneUplink(
    private val context: Context,
    private val config: MicrophoneConfig,
    microphoneGainPercent: Int,
    /** AudioRecord source chosen by the caller (see [microphoneSourceFor]); MIC by default. */
    private val source: Int = android.media.MediaRecorder.AudioSource.MIC,
    /** Session-log sink: the uploaded log only contains these lines, never logcat. */
    private val log: (String) -> Unit = {},
) : Closeable {
    private val microphoneGainPercent = MicrophoneGain.sanitize(microphoneGainPercent)
    private val running = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)
    @Volatile private var audioModeLease: Closeable? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    @Volatile private var activeSource: Int = source
    @Volatile private var lastPeakPercent: Int = 0
    private var escalateLevel = 0
    private var recorderBufferBytes = 0
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            startCapture()
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone start failed", error)
            log("Audio: microphone uplink failed type=${config.audioType}: ${error.message ?: error.javaClass.simpleName}")
            release()
            false
        }
    }

    private fun startCapture() {
        // A call owns the communication route; a voice memo (WeChat/QQ) or Siri does not, and
        // switching the device into communication mode for them rerouted the head unit audio and
        // left the recording silent. See [microphoneNeedsCommunicationMode].
        if (microphoneNeedsCommunicationMode(config.audioType)) {
            audioModeLease = MicrophoneAudioMode.acquire(context)
        }

        if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder = OpusEncoder(
                config.sampleRate,
                config.bitrate ?: 48_000,
                onFallback = { reason ->
                    Log.w(TAG, "Opus encoder fallback: $reason")
                    log("Audio: Opus encoder fallback ($reason)")
                },
            )
        }

        val captureFrameBytes =
            MICROPHONE_CAPTURE_RATE_HZ * config.frameMillis / 1000 * BYTES_PER_SAMPLE
        recorderBufferBytes = captureFrameBytes * 4
        val nextRecorder = openMicrophoneRecorder(recorderBufferBytes, source)
        recorder = nextRecorder

        val nextSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(microphoneBindAddress(config.host), 0))
        }
        socket = nextSocket

        nextRecorder.startRecording()
        thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
            isDaemon = true
            start()
        }
        Log.i(
            TAG,
            "microphone uplink started type=${config.audioType} codec=${config.codec} " +
                "captureRate=$MICROPHONE_CAPTURE_RATE_HZ outputRate=${config.sampleRate} " +
                "channels=${config.channels} gain=${microphoneGainPercent}% " +
                "frameMs=${config.frameMillis} port=${config.port} " +
                "commMode=${microphoneNeedsCommunicationMode(config.audioType)}",
        )
        log(
            "Audio: uplink start type=${config.audioType} codec=${config.codec} " +
                "source=${sourceName(source)} rate=${config.sampleRate} " +
                "frameMs=${config.frameMillis} targetPort=${config.port} " +
                "commMode=${microphoneNeedsCommunicationMode(config.audioType)}",
        )
    }

    private fun capture(activeRecorder: AudioRecord, activeSocket: DatagramSocket) {
        val frame = ByteArray(config.samplesPerPacket * BYTES_PER_SAMPLE)
        val readBuffer = ByteArray(maxOf(CAPTURE_READ_BYTES, frame.size))
        val resampler = if (config.sampleRate == MICROPHONE_CAPTURE_RATE_HZ) {
            null
        } else {
            PcmMonoResampler(MICROPHONE_CAPTURE_RATE_HZ, config.sampleRate)
        }
        val counters = MicrophoneCounters()
        var current = activeRecorder
        var filled = 0
        var silentBytes = 0L
        var lastLevelLogAt = 0L
        try {
            while (running.get()) {
                val count = current.read(
                    readBuffer,
                    0,
                    readBuffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        log("Audio: microphone read failed code=$count source=${sourceName(activeSource)}")
                    }
                    return
                }
                if (count == 0) continue

                val peak = MicrophoneGain.applyPcm16InPlace(readBuffer, count, microphoneGainPercent)
                val peakPercent = MicrophoneGain.peakPercent(peak)
                lastPeakPercent = peakPercent
                silentBytes = if (peak == 0) silentBytes + count else 0L
                val now = System.currentTimeMillis()
                if (now - lastLevelLogAt >= LEVEL_LOG_MILLIS) {
                    lastLevelLogAt = now
                    log(
                        "Audio: microphone level type=${config.audioType} " +
                            "source=${sourceName(activeSource)} peak=$peakPercent%",
                    )
                }
                // Digital silence for a while means the head unit never routed this source (some
                // units mute VOICE_RECOGNITION outside communication mode): walk the source ladder
                // once instead of recording a blank voice message.
                if (silentBytes >= SILENCE_ESCALATE_BYTES &&
                    config.audioType != "telephony" &&
                    escalateLevel < MAX_ESCALATE_LEVEL
                ) {
                    silentBytes = 0L
                    val next = escalateSource(current)
                    if (next != null) {
                        current = next
                        filled = 0
                    }
                }
                val samples = resampler?.convert(readBuffer, 0, count) ?: readBuffer
                val sampleBytes = if (resampler == null) count else samples.size
                var offset = 0
                while (offset < sampleBytes && running.get()) {
                    val copied = minOf(frame.size - filled, sampleBytes - offset)
                    samples.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(activeSocket, counters, frame)
                        filled = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                log("Audio: microphone capture failed: ${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            running.set(false)
            try {
                current.stop()
            } catch (_: Exception) {
                // The recorder may already be stopped by close().
            }
            release()
        }
    }

    /**
     * Walks the capture-source ladder after digital silence: VOICE_COMMUNICATION (the head unit's
     * call path, with communication mode so its routing engages), then plain MIC. The next recorder
     * is opened before the old one is touched, so a refused source leaves the current capture alive.
     */
    private fun escalateSource(current: AudioRecord): AudioRecord? {
        val nextLevel = escalateLevel + 1
        val nextSource = when (nextLevel) {
            1 -> android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> android.media.MediaRecorder.AudioSource.MIC
        }
        log(
            "Audio: microphone source switch type=${config.audioType} " +
                "from=${sourceName(activeSource)} to=${sourceName(nextSource)} " +
                "reason=silence",
        )
        val next = try {
            openMicrophoneRecorder(recorderBufferBytes, nextSource).also { it.startRecording() }
        } catch (error: Exception) {
            Log.e(TAG, "microphone source switch failed", error)
            log("Audio: microphone source switch failed to=${sourceName(nextSource)}")
            escalateLevel = MAX_ESCALATE_LEVEL
            return null
        }
        if (audioModeLease == null) {
            audioModeLease = runCatching { MicrophoneAudioMode.acquire(context) }
                .onFailure { log("Audio: microphone mode acquire failed: ${it.message}") }
                .getOrNull()
        }
        escalateLevel = nextLevel
        activeSource = nextSource
        recorder = next
        try {
            current.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            current.release()
        } catch (_: Exception) {
            // Best effort.
        }
        return next
    }

    private fun sendFrame(
        activeSocket: DatagramSocket,
        counters: MicrophoneCounters,
        monoFrame: ByteArray,
    ) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(monoFrame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(expandMonoPcm(monoFrame, config.channels)))
        }
        bodies.forEach { body ->
            val packet = MicrophonePacketizer.sealPacket(
                key = config.key,
                payloadType = config.payloadType,
                counters = counters,
                body = body,
                samples = config.rtpSamplesPerPacket,
            )
            try {
                activeSocket.send(DatagramPacket(packet, packet.size, config.host, config.port))
                if (firstPacketLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "microphone first packet bytes=${packet.size} body=${body.size} " +
                            "head=${packet.copyOf(minOf(packet.size, 16)).toHexString()} " +
                            "peer=${config.host.hostAddress}:${config.port}",
                    )
                    log(
                        "Audio: microphone first packet type=${config.audioType} " +
                            "bytes=${packet.size} body=${body.size} peak=$lastPeakPercent% " +
                            "source=${sourceName(activeSource)} " +
                            "head=${packet.copyOf(minOf(packet.size, 16)).toHexString()}",
                    )
                }
            } catch (error: Exception) {
                if (running.get()) throw error
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)

        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }

        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }

        val currentEncoder = opusEncoder
        opusEncoder = null
        try {
            currentEncoder?.close()
        } catch (_: Exception) {
            // Best effort.
        }

        val currentAudioModeLease = audioModeLease
        audioModeLease = null
        try {
            currentAudioModeLease?.close()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val TAG = "hplay-usb"
        const val BYTES_PER_SAMPLE = 2
        const val CAPTURE_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        /** One level line per second keeps the session log readable. */
        const val LEVEL_LOG_MILLIS = 1_000L
        /** 300 ms of 16 kHz mono PCM: the point where a silent capture is treated as misrouted. */
        const val SILENCE_ESCALATE_BYTES = 16_000L * BYTES_PER_SAMPLE * 300 / 1_000
        const val MAX_ESCALATE_LEVEL = 2
    }
}
