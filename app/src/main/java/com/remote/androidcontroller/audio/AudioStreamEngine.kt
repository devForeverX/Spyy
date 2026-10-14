package com.remote.androidcontroller.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streams decoded audio as bounded PCM frames over the authenticated WebRTC audio data channel.
 * Every transmitted PCM frame is normalized to 44.1 kHz, stereo, signed 16-bit little-endian PCM.
 */
class AudioStreamEngine(
    private val context: Context,
    private val onSendBinaryPacket: (ByteArray) -> Boolean,
    private val onSendFlowControl: (AudioFlowState) -> Unit,
    private val onAudioStatusChanged: (isPlaying: Boolean, isPaused: Boolean, posMs: Long) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        const val FRAME_SIZE_BYTES = 4096 // about 23 ms of 16-bit 44.1 kHz stereo PCM
        private const val PCM_FRAME_BYTES = CHANNELS * 2
    }

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var streamingJob: Job? = null

    private val jitterBuffer = JitterBuffer(
        sampleRate = SAMPLE_RATE,
        channels = CHANNELS,
        onHighWatermark = { onSendFlowControl(AudioFlowState.PAUSE) },
        onLowWatermark = { onSendFlowControl(AudioFlowState.RESUME) }
    )

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    var isPaused = false
        private set

    @Volatile
    var isFlowPausedByReceiver = false

    @Volatile
    private var isControllerPaused = false

    private var sequenceCounter = 0L
    @Volatile private var streamEndReceived = false
    @Volatile private var controllerPositionMs = 0L
    @Volatile private var hostVolume = 1f

    /** Create a real system AudioTrack on the target phone. */
    fun startHostPlayback() {
        if (audioTrack != null) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            onError("Android kann kein 44,1-kHz-Stereo-Audio ausgeben.")
            return
        }

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        try {
            audioTrack = AudioTrack(
                attributes,
                format,
                (minBufferSize * 2).coerceAtLeast(8192),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            ).also { track ->
                check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack konnte nicht initialisiert werden" }
                track.setVolume(hostVolume)
                track.play()
            }
        } catch (e: Exception) {
            audioTrack?.release()
            audioTrack = null
            onError("Audioausgabe konnte nicht gestartet werden: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        isPlaying = true
        isPaused = false
        streamEndReceived = false
        jitterBuffer.reset()
        playbackJob?.cancel()
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isPlaying) {
                if (isPaused) {
                    delay(20)
                    continue
                }
                val frame = jitterBuffer.pop()
                if (frame != null) {
                    try {
                        val track = audioTrack ?: break
                        var offset = 0
                        while (offset < frame.size && isActive && isPlaying && !isPaused) {
                            val written = track.write(frame, offset, frame.size - offset, AudioTrack.WRITE_BLOCKING)
                            if (written < 0) throw IOException("AudioTrack write fehlgeschlagen: $written")
                            if (written == 0) delay(2) else offset += written
                        }
                        onAudioStatusChanged(true, false, jitterBuffer.currentPlaybackPtsMs)
                    } catch (e: Exception) {
                        onError("Audioausgabe unterbrochen: ${e.message ?: e.javaClass.simpleName}")
                        stopHostPlayback()
                    }
                } else if (streamEndReceived && jitterBuffer.getBufferFillPercent() == 0) {
                    stopHostPlayback()
                    break
                } else {
                    delay(5) // wait for pre-buffering
                }
            }
        }
    }

    /** Receive a validated binary audio packet from the remote WebRTC data channel. */
    fun handleIncomingBinaryPacket(packetBytes: ByteArray) {
        val packet = AudioPacket.decode(packetBytes) ?: return
        when (packet.type) {
            AudioPacket.TYPE_STREAM_HEADER -> startHostPlayback()
            AudioPacket.TYPE_PCM_DATA -> if (isPlaying) jitterBuffer.push(packet)
            AudioPacket.TYPE_PAUSE -> pauseHostPlayback()
            AudioPacket.TYPE_STREAM_END -> {
                streamEndReceived = true
                jitterBuffer.markEndOfStream()
                if (jitterBuffer.getBufferFillPercent() == 0) stopHostPlayback()
            }
        }
    }

    fun pauseHostPlayback() {
        if (!isPlaying) return
        isPaused = true
        try { audioTrack?.pause() } catch (_: IllegalStateException) { }
        onAudioStatusChanged(true, true, jitterBuffer.currentPlaybackPtsMs)
    }

    fun resumeHostPlayback() {
        if (!isPlaying || audioTrack == null) return
        try { audioTrack?.play() } catch (e: IllegalStateException) {
            onError("Audioausgabe kann nicht fortgesetzt werden: ${e.message}")
            return
        }
        isPaused = false
        onAudioStatusChanged(true, false, jitterBuffer.currentPlaybackPtsMs)
    }

    fun stopHostPlayback() {
        isPlaying = false
        isPaused = false
        streamEndReceived = false
        playbackJob?.cancel()
        playbackJob = null
        audioTrack?.let { track ->
            try { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() } catch (_: Exception) { }
            try { track.release() } catch (_: Exception) { }
        }
        audioTrack = null
        jitterBuffer.reset()
        onAudioStatusChanged(false, false, 0L)
    }

    fun setHostVolume(volumePercent: Float) {
        hostVolume = volumePercent.coerceIn(0f, 1f)
        audioTrack?.setVolume(hostVolume)
    }

    /** Decode the selected SAF file with MediaCodec, normalize PCM, and send bounded data-channel frames. */
    fun startControllerStreaming(uri: Uri) {
        stopControllerStreaming()
        isControllerPaused = false
        isFlowPausedByReceiver = false
        controllerPositionMs = 0L
        sequenceCounter = 0L

        streamingJob = CoroutineScope(Dispatchers.IO).launch {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            var sentHeader = false
            var currentPtsMs = 0L
            try {
                extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
                var audioTrackIndex = -1
                var sourceFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val candidate = extractor.getTrackFormat(i)
                    if ((candidate.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                        audioTrackIndex = i
                        sourceFormat = candidate
                        break
                    }
                }
                if (audioTrackIndex < 0 || sourceFormat == null) {
                    throw IOException("Die ausgewählte Datei enthält keine unterstützte Audiospur.")
                }

                extractor.selectTrack(audioTrackIndex)
                val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IOException("Audioformat enthält keinen MIME-Typ.")
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(sourceFormat, null, null, 0)
                codec.start()

                var outputSampleRate = sourceFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
                var outputChannels = sourceFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS)
                var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
                var inputEosQueued = false
                var outputEosReached = false
                val bufferInfo = MediaCodec.BufferInfo()
                val timeoutUs = 10_000L

                while (isActive && !outputEosReached) {
                    while ((isFlowPausedByReceiver || isControllerPaused) && isActive) delay(20)

                    if (!inputEosQueued) {
                        val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                                ?: throw IOException("MediaCodec lieferte keinen Eingabepuffer.")
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEosQueued = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val decodedFormat = codec.outputFormat
                            outputSampleRate = decodedFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, outputSampleRate)
                            outputChannels = decodedFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, outputChannels)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && decodedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputEncoding = decodedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                        outputIndex >= 0 -> {
                            if (bufferInfo.size > 0) {
                                val outputBuffer = codec.getOutputBuffer(outputIndex)
                                    ?: throw IOException("MediaCodec lieferte keinen Ausgabepuffer.")
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val decodedPcm = ByteArray(bufferInfo.size)
                                outputBuffer.get(decodedPcm)
                                val normalized = normalizeToStereoPcm16(decodedPcm, outputSampleRate, outputChannels, outputEncoding)
                                if (normalized.isNotEmpty()) {
                                    if (!sentHeader) {
                                        val header = AudioPacket.encode(AudioPacket.TYPE_STREAM_HEADER, sequenceCounter++, 0L, byteArrayOf())
                                        if (!onSendBinaryPacket(header)) throw IOException("Audio-Datenkanal ist nicht geöffnet; bitte erneut verbinden.")
                                        sentHeader = true
                                        onAudioStatusChanged(true, false, 0L)
                                    }
                                    var offset = 0
                                    while (offset < normalized.size && isActive) {
                                        while ((isFlowPausedByReceiver || isControllerPaused) && isActive) delay(20)
                                        val remaining = normalized.size - offset
                                        var length = minOf(FRAME_SIZE_BYTES, remaining)
                                        length -= length % PCM_FRAME_BYTES
                                        if (length <= 0) break
                                        val frameData = normalized.copyOfRange(offset, offset + length)
                                        val frame = AudioPacket.encode(
                                            AudioPacket.TYPE_PCM_DATA,
                                            sequenceCounter++,
                                            currentPtsMs,
                                            frameData
                                        )
                                        if (!onSendBinaryPacket(frame)) throw IOException("Audio-Datenkanal wurde während der Übertragung geschlossen.")
                                        currentPtsMs += ((length.toDouble() / (SAMPLE_RATE * PCM_FRAME_BYTES)) * 1000.0).roundToInt()
                                        controllerPositionMs = currentPtsMs
                                        onAudioStatusChanged(true, isControllerPaused, currentPtsMs)
                                        offset += length
                                        val delayMs = ((length.toDouble() / (SAMPLE_RATE * PCM_FRAME_BYTES)) * 1000.0).roundToInt()
                                        if (delayMs > 0) delay(delayMs.toLong())
                                    }
                                }
                            }
                            outputEosReached = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }

                if (!sentHeader) throw IOException("Die Datei lieferte keine decodierbaren Audiodaten.")
                val endPacket = AudioPacket.encode(AudioPacket.TYPE_STREAM_END, sequenceCounter++, currentPtsMs, byteArrayOf())
                onSendBinaryPacket(endPacket)
                onAudioStatusChanged(false, false, currentPtsMs)
            } catch (e: Exception) {
                onError("Audioübertragung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
                onAudioStatusChanged(false, false, currentPtsMs)
            } finally {
                try { codec?.stop() } catch (_: Exception) { }
                try { codec?.release() } catch (_: Exception) { }
                try { extractor?.release() } catch (_: Exception) { }
                streamingJob = null
            }
        }
    }

    fun pauseControllerStreaming() {
        isControllerPaused = true
        onAudioStatusChanged(streamingJob?.isActive == true, true, controllerPositionMs)
    }

    fun resumeControllerStreaming() {
        isControllerPaused = false
        onAudioStatusChanged(streamingJob?.isActive == true, false, controllerPositionMs)
    }

    /** Generates and plays a local tone when explicitly invoked. */
    fun startLocalTestTone(durationMs: Int = 3000) {
        CoroutineScope(Dispatchers.IO).launch {
            val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return@launch
            val track = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                minBuffer, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            try {
                track.play()
                val sampleCount = (SAMPLE_RATE * (durationMs / 1000.0)).toInt()
                val samples = ShortArray(sampleCount)
                for (i in samples.indices) {
                    samples[i] = (sin(2.0 * Math.PI * 880.0 * i / SAMPLE_RATE) * 32767).toInt().toShort()
                }
                track.write(samples, 0, samples.size)
                delay(durationMs.toLong() + 100)
            } finally {
                try { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() } catch (_: Exception) { }
                track.release()
            }
        }
    }

    fun stopControllerStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        isControllerPaused = false
        isFlowPausedByReceiver = false
    }

    fun getBufferFillPercent(): Int = jitterBuffer.getBufferFillPercent()
    fun getUnderrunCount(): Int = jitterBuffer.underrunCount

    private fun normalizeToStereoPcm16(raw: ByteArray, inputSampleRate: Int, inputChannels: Int, inputEncoding: Int): ByteArray {
        require(inputSampleRate > 0) { "Ungültige Abtastrate: $inputSampleRate" }
        require(inputChannels > 0 && inputChannels <= 8) { "Ungültige Kanalanzahl: $inputChannels" }
        val bytesPerSample = when (inputEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> throw IOException("Nicht unterstützte decodierte PCM-Kodierung: $inputEncoding")
        }
        val bytesPerInputFrame = bytesPerSample * inputChannels
        val inputFrames = raw.size / bytesPerInputFrame
        if (inputFrames <= 0) return byteArrayOf()

        val outputFrames = (inputFrames.toDouble() * SAMPLE_RATE / inputSampleRate).roundToInt().coerceAtLeast(1)
        val output = ByteBuffer.allocate(outputFrames * PCM_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val input = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        fun readSample(frame: Int, channel: Int): Float {
            val sampleIndex = frame * inputChannels + channel.coerceAtMost(inputChannels - 1)
            return if (inputEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                input.getFloat(sampleIndex * 4).coerceIn(-1f, 1f)
            } else {
                input.getShort(sampleIndex * 2) / 32768f
            }
        }

        for (outFrame in 0 until outputFrames) {
            val sourcePosition = outFrame.toDouble() * inputSampleRate / SAMPLE_RATE
            val first = floor(sourcePosition).toInt().coerceIn(0, inputFrames - 1)
            val second = (first + 1).coerceAtMost(inputFrames - 1)
            val fraction = (sourcePosition - floor(sourcePosition)).toFloat()
            val left = readSample(first, 0) * (1f - fraction) + readSample(second, 0) * fraction
            val right = if (inputChannels == 1) left else {
                readSample(first, 1) * (1f - fraction) + readSample(second, 1) * fraction
            }
            output.putShort((left.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
            output.putShort((right.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        }
        return output.array()
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}
