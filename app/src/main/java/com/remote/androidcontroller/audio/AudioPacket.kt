package com.remote.androidcontroller.audio

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Serializable
enum class AudioFlowState {
    PAUSE,
    RESUME
}

/**
 * Binary Audio Packet specification for WebRTC DataChannel transmission.
 * Avoids inefficient JSON or Base64 encoding.
 *
 * Header Structure (17 Bytes):
 * [0..1]   Magic Bytes: 0x41 0x55 ("AU")
 * [2]      Packet Type (0x01=Header, 0x02=PCM Data, 0x03=Pause, 0x04=End)
 * [3..6]   32-bit Monotonic Sequence Number (Big Endian)
 * [7..14]  64-bit Presentation Timestamp (PTS in ms) (Big Endian)
 * [15..16] 16-bit Payload Length N (Big Endian)
 * [17..17+N] Raw PCM 16-Bit 44.1 kHz Stereo Samples
 */
object AudioPacket {
    const val MAGIC_BYTE_1: Byte = 0x41 // 'A'
    const val MAGIC_BYTE_2: Byte = 0x55 // 'U'
    const val HEADER_SIZE = 17

    const val TYPE_STREAM_HEADER: Byte = 0x01
    const val TYPE_PCM_DATA: Byte = 0x02
    const val TYPE_PAUSE: Byte = 0x03
    const val TYPE_STREAM_END: Byte = 0x04

    data class DecodedPacket(
        val type: Byte,
        val sequenceNumber: Long,
        val ptsMs: Long,
        val payload: ByteArray
    )

    fun encode(
        type: Byte,
        sequenceNumber: Long,
        ptsMs: Long,
        payload: ByteArray
    ): ByteArray {
        require(payload.size <= 65535) { "Payload too large for 16-bit length" }

        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(MAGIC_BYTE_1)
            put(MAGIC_BYTE_2)
            put(type)
            putInt(sequenceNumber.toInt())
            putLong(ptsMs)
            putShort(payload.size.toShort())
            put(payload)
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): DecodedPacket? {
        if (bytes.size < HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(bytes).apply {
            order(ByteOrder.BIG_ENDIAN)
        }

        val b1 = buffer.get()
        val b2 = buffer.get()
        if (b1 != MAGIC_BYTE_1 || b2 != MAGIC_BYTE_2) {
            return null // Invalid magic
        }

        val type = buffer.get()
        if (type != TYPE_STREAM_HEADER && type != TYPE_PCM_DATA && type != TYPE_PAUSE && type != TYPE_STREAM_END) {
            return null // Unknown packet type
        }

        val seq = buffer.getInt().toLong() and 0xFFFFFFFFL
        val pts = buffer.getLong()
        val payloadLen = buffer.getShort().toInt() and 0xFFFF

        // Enforce exact length matching: no truncated and no oversized packets
        if (bytes.size != HEADER_SIZE + payloadLen || buffer.remaining() != payloadLen) {
            return null
        }

        val payload = ByteArray(payloadLen)
        buffer.get(payload)

        return DecodedPacket(type, seq, pts, payload)
    }
}
