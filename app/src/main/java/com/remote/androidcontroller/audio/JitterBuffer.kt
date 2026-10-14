package com.remote.androidcontroller.audio

import java.util.concurrent.ConcurrentSkipListMap

/**
 * Bounded Jitter Buffer for smooth PCM audio playback over WebRTC.
 * Sorts incoming frames by monotonic sequence number and Presentation Timestamp (PTS).
 * Enforces bounded buffer size (max 250 ms) and provides flow-control watermarks.
 */
class JitterBuffer(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    private val bytesPerSample: Int = 2, // 16-bit PCM
    private val maxBufferDurationMs: Long = 250L,
    private val preBufferDurationMs: Long = 100L,
    private val onHighWatermark: () -> Unit = {},
    private val onLowWatermark: () -> Unit = {}
) {
    // Bytes per millisecond: 44100 * 2 * 2 / 1000 = 176.4 bytes/ms
    private val bytesPerMs = (sampleRate * channels * bytesPerSample) / 1000.0
    private val maxBufferBytes = (bytesPerMs * maxBufferDurationMs).toInt()
    private val preBufferBytes = (bytesPerMs * preBufferDurationMs).toInt()
    private val highWatermarkBytes = (bytesPerMs * 200).toInt()
    private val lowWatermarkBytes = (bytesPerMs * 80).toInt()

    private val packetQueue = ConcurrentSkipListMap<Long, AudioPacket.DecodedPacket>()
    private val lock = Any()

    @Volatile
    private var totalBufferedBytes = 0

    @Volatile
    var isPreBuffered = false
        private set

    @Volatile
    var underrunCount = 0
        private set

    @Volatile
    private var isHighWatermarkSignaled = false

    @Volatile
    var currentPlaybackPtsMs: Long = 0L
        private set

    fun push(packet: AudioPacket.DecodedPacket) {
        synchronized(lock) {
            // Drop packets that arrive too late (>50ms after current playback PTS)
            if (currentPlaybackPtsMs > 0 && packet.ptsMs < currentPlaybackPtsMs - 50) {
                return
            }

            // If buffer is overflowing, drop oldest packet to protect memory
            while (packetQueue.isNotEmpty() && totalBufferedBytes + packet.payload.size > maxBufferBytes) {
                val firstKey = packetQueue.firstKey()
                val dropped = packetQueue.remove(firstKey)
                if (dropped != null) {
                    totalBufferedBytes -= dropped.payload.size
                }
            }

            val existing = packetQueue.put(packet.sequenceNumber, packet)
            if (existing != null) {
                totalBufferedBytes -= existing.payload.size
            }
            totalBufferedBytes += packet.payload.size

            if (!isPreBuffered && totalBufferedBytes >= preBufferBytes) {
                isPreBuffered = true
            }

            if (!isHighWatermarkSignaled && totalBufferedBytes >= highWatermarkBytes) {
                isHighWatermarkSignaled = true
                onHighWatermark()
            }
        }
    }

    /**
     * Pulls the next in-order PCM frame payload.
     * Returns null if buffer is empty or pre-buffering.
     */
    fun pop(): ByteArray? {
        synchronized(lock) {
            if (!isPreBuffered || packetQueue.isEmpty()) {
                if (isPreBuffered && packetQueue.isEmpty()) {
                    underrunCount++
                    isPreBuffered = false // Needs to pre-buffer again
                }
                return null
            }

            val entry = packetQueue.pollFirstEntry() ?: return null
            val packet = entry.value
            totalBufferedBytes -= packet.payload.size
            currentPlaybackPtsMs = packet.ptsMs

            if (isHighWatermarkSignaled && totalBufferedBytes <= lowWatermarkBytes) {
                isHighWatermarkSignaled = false
                onLowWatermark()
            }

            return packet.payload
        }
    }

    /** Allows a short final tail (under the normal prebuffer threshold) to play after end-of-stream. */
    fun markEndOfStream() {
        synchronized(lock) { isPreBuffered = true }
    }

    fun getBufferFillPercent(): Int {
        val pct = (totalBufferedBytes.toDouble() / maxBufferBytes.toDouble() * 100).toInt()
        return pct.coerceIn(0, 100)
    }

    fun reset() {
        synchronized(lock) {
            packetQueue.clear()
            totalBufferedBytes = 0
            isPreBuffered = false
            currentPlaybackPtsMs = 0L
            isHighWatermarkSignaled = false
        }
    }
}
