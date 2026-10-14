package com.remote.androidcontroller

import com.remote.androidcontroller.audio.AudioPacket
import com.remote.androidcontroller.audio.JitterBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioJitterBufferTest {

    @Test
    fun testAudioPacketEncodingAndDecoding() {
        val payload = "TEST_PCM_DATA_SAMPLE_1234".toByteArray()
        val seq = 42L
        val pts = 1200L

        val encoded = AudioPacket.encode(
            type = AudioPacket.TYPE_PCM_DATA,
            sequenceNumber = seq,
            ptsMs = pts,
            payload = payload
        )

        val decoded = AudioPacket.decode(encoded)
        assertNotNull(decoded)
        assertEquals(AudioPacket.TYPE_PCM_DATA, decoded!!.type)
        assertEquals(seq, decoded.sequenceNumber)
        assertEquals(pts, decoded.ptsMs)
        assertEquals(String(payload), String(decoded.payload))
    }

    @Test
    fun testJitterBufferPrebufferingAndWatermarks() {
        var highWatermarkHit = false
        var lowWatermarkHit = false

        val buffer = JitterBuffer(
            sampleRate = 44100,
            channels = 2,
            onHighWatermark = { highWatermarkHit = true },
            onLowWatermark = { lowWatermarkHit = true }
        )

        assertFalse(buffer.isPreBuffered)

        // Push frames
        val dummyPayload = ByteArray(4096)
        for (i in 0 until 10) {
            val packet = AudioPacket.DecodedPacket(
                type = AudioPacket.TYPE_PCM_DATA,
                sequenceNumber = i.toLong(),
                ptsMs = (i * 23).toLong(),
                payload = dummyPayload
            )
            buffer.push(packet)
        }

        assertTrue(buffer.isPreBuffered)
        assertTrue(buffer.getBufferFillPercent() > 0)

        // Pop in order
        val firstPopped = buffer.pop()
        assertNotNull(firstPopped)
        assertEquals(4096, firstPopped!!.size)
    }

    @Test
    fun testLateFrameDrop() {
        val buffer = JitterBuffer(sampleRate = 44100, channels = 2)

        // Push frame at PTS 500
        val p1 = AudioPacket.DecodedPacket(AudioPacket.TYPE_PCM_DATA, 1, 500, ByteArray(20000))
        buffer.push(p1)
        buffer.pop() // Sets currentPlaybackPtsMs to 500

        // Push late frame with PTS 300 (< 500 - 50)
        val latePacket = AudioPacket.DecodedPacket(AudioPacket.TYPE_PCM_DATA, 2, 300, ByteArray(4096))
        buffer.push(latePacket)

        // Late frame should have been dropped
        val popped = buffer.pop()
        assertNull(popped)
    }
}
