package com.remote.androidcontroller.network

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

interface WebRtcListener {
    fun onIceCandidateGenerated(candidate: IceCandidate)
    fun onConnectionStateChange(newState: PeerConnection.PeerConnectionState)
    fun onDataChannelStateChange(channelLabel: String, isOpen: Boolean)
    fun onControlMessageReceived(messageJson: String)
    fun onAudioBinaryReceived(bytes: ByteArray)
    fun onRemoteVideoTrack(track: VideoTrack?)
    fun onScreenCaptureStateChanged(active: Boolean, pending: Boolean, message: String?)
    fun onRttMeasured(rttMs: Long, isRelayed: Boolean)
    fun onError(reason: String)
}

class WebRtcPeerManager(
    private val context: Context,
    private val listener: WebRtcListener
) {
    companion object {
        private const val MAX_CONTROL_MESSAGE_BYTES = 64 * 1024
    }
    private val eglBase: EglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var controlDataChannel: DataChannel? = null
    private var audioDataChannel: DataChannel? = null

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false
    private var isRelayedConnection = false

    private var pingSendTimestamp = 0L

    // Kept across PeerConnection recovery so screen capture can continue with a fresh ICE path.
    private var screenVideoSource: VideoSource? = null
    private var localScreenTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var pendingScreenPermission: Pair<Int, Intent>? = null
    @Volatile private var screenCaptureActive: Boolean = false

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun initializePeerConnection(iceServers: List<PeerConnection.IceServer>) {
        resetPeerConnection()
        isRelayedConnection = false
        isRemoteDescriptionSet = false
        pendingIceCandidates.clear()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    queryConnectionStats()
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onIceCandidateGenerated(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onAddStream(stream: org.webrtc.MediaStream) {}

            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}

            override fun onDataChannel(dataChannel: DataChannel) {
                setupIncomingDataChannel(dataChannel)
            }

            override fun onRenegotiationNeeded() {}

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                listener.onConnectionStateChange(newState)
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    queryConnectionStats()
                }
            }

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                val videoTrack = transceiver?.receiver?.track() as? VideoTrack
                if (videoTrack != null) {
                    videoTrack.setEnabled(true)
                    listener.onRemoteVideoTrack(videoTrack)
                }
            }
        })

        val pc = peerConnection
        if (pc == null) {
            listener.onError("WebRTC konnte keine PeerConnection erstellen.")
            return
        }
        try {
            prepareLocalScreenTrack(pc)
            startPendingScreenCaptureIfReady()
        } catch (e: Exception) {
            listener.onError("Video-Track konnte nicht initialisiert werden: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun createDataChannels() {
        val pc = peerConnection ?: return

        // 1. Reliable Control Channel
        val controlInit = DataChannel.Init().apply {
            ordered = true
        }
        controlDataChannel = pc.createDataChannel("control", controlInit).also {
            setupControlChannelObserver(it)
        }

        // 2. High-Throughput Binary Audio Channel
        val audioInit = DataChannel.Init().apply {
            ordered = true
        }
        audioDataChannel = pc.createDataChannel("audio-binary", audioInit).also {
            setupAudioChannelObserver(it)
        }
    }

    private fun setupIncomingDataChannel(dataChannel: DataChannel) {
        when (dataChannel.label()) {
            "control" -> {
                controlDataChannel = dataChannel
                setupControlChannelObserver(dataChannel)
            }
            "audio-binary" -> {
                audioDataChannel = dataChannel
                setupAudioChannelObserver(dataChannel)
            }
        }
    }

    private fun setupControlChannelObserver(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val isOpen = channel.state() == DataChannel.State.OPEN
                listener.onDataChannelStateChange("control", isOpen)
                if (isOpen) queryConnectionStats()
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.data.remaining() > MAX_CONTROL_MESSAGE_BYTES) {
                    listener.onError("Steuernachricht verworfen: Größenlimit von 64 KiB überschritten.")
                    return
                }
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val text = String(data, Charsets.UTF_8)

                if (text.startsWith("PING:")) {
                    channel.send(DataChannel.Buffer(ByteBuffer.wrap("PONG:${text.removePrefix("PING:")}".toByteArray()), false))
                } else if (text.startsWith("PONG:")) {
                    val sentTime = text.removePrefix("PONG:").toLongOrNull() ?: 0L
                    if (sentTime > 0) {
                        val rtt = System.currentTimeMillis() - sentTime
                        listener.onRttMeasured(rtt, isRelayedConnection)
                    }
                } else {
                    listener.onControlMessageReceived(text)
                }
            }
        })
    }

    private fun setupAudioChannelObserver(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val isOpen = channel.state() == DataChannel.State.OPEN
                listener.onDataChannelStateChange("audio-binary", isOpen)
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                listener.onAudioBinaryReceived(data)
            }
        })
    }

    fun sendControlMessage(jsonText: String): Boolean {
        val channel = controlDataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val bytes = jsonText.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CONTROL_MESSAGE_BYTES) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    fun isAudioChannelOpen(): Boolean = audioDataChannel?.state() == DataChannel.State.OPEN

    fun sendAudioBinary(bytes: ByteArray): Boolean {
        val channel = audioDataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), true)
        return channel.send(buffer)
    }

    fun measureRtt() {
        val channel = controlDataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        pingSendTimestamp = System.currentTimeMillis()
        val ping = "PING:$pingSendTimestamp"
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(ping.toByteArray()), false))
    }

    fun queryConnectionStats(attempt: Int = 0) {
        val queriedPeer = peerConnection ?: return
        queriedPeer.getStats { report: RTCStatsReport ->
            // Ignore asynchronous reports belonging to a peer that was replaced during recovery.
            if (queriedPeer !== peerConnection) return@getStats
            val candidatePairs = report.statsMap.values.filter {
                it.type == "candidate-pair" && it.members["state"] == "succeeded"
            }

            // Prefer the selected candidate pair identified by a transport stat. Some bundled
            // WebRTC versions expose a Boolean here, others a string. A nominated pair is the
            // next-best source. Never classify an arbitrary successful candidate as the path
            // when multiple pairs exist; that can report P2P while the selected route uses TURN.
            val transportSelectedId = report.statsMap.values
                .firstOrNull { it.type == "transport" }
                ?.members?.get("selectedCandidatePairId") as? String
            val selectedPair = candidatePairs.firstOrNull { it.id == transportSelectedId }
                ?: candidatePairs.firstOrNull {
                    val nominated = it.members["nominated"]
                    nominated == true || nominated == "true"
                }
                ?: candidatePairs.singleOrNull()

            if (selectedPair != null) {
                val localCandidateId = selectedPair.members["localCandidateId"] as? String
                val remoteCandidateId = selectedPair.members["remoteCandidateId"] as? String
                val localType = report.statsMap[localCandidateId]?.members?.get("candidateType") as? String
                val remoteType = report.statsMap[remoteCandidateId]?.members?.get("candidateType") as? String
                if (localType != null && remoteType != null) {
                    isRelayedConnection = localType == "relay" || remoteType == "relay"
                    // RTT is updated only after the route has been determined from ICE stats.
                    measureRtt()
                    return@getStats
                }
            }
            // The control channel may open just before the selected-pair stats are published. Retry
            // a bounded number of times so the UI can learn the route without claiming a guess.
            if (attempt < 5) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (queriedPeer === peerConnection) queryConnectionStats(attempt + 1)
                }, 500L)
            }
        }
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { onSuccess(desc) }
                    override fun onCreateFailure(p0: String?) { onError(p0 ?: "Failed to set local offer") }
                    override fun onSetFailure(p0: String?) { onError(p0 ?: "Failed to set local offer") }
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { onError(p0 ?: "Failed to create offer") }
            override fun onSetFailure(p0: String?) { onError(p0 ?: "Failed to set description") }
        }, constraints)
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { onSuccess(desc) }
                    override fun onCreateFailure(p0: String?) { onError(p0 ?: "Failed to set local answer") }
                    override fun onSetFailure(p0: String?) { onError(p0 ?: "Failed to set local answer") }
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { onError(p0 ?: "Failed to create answer") }
            override fun onSetFailure(p0: String?) { onError(p0 ?: "Failed to set description") }
        }, constraints)
    }

    fun setRemoteDescription(desc: SessionDescription, onComplete: () -> Unit, onError: (String) -> Unit) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                synchronized(pendingIceCandidates) {
                    for (cand in pendingIceCandidates) {
                        peerConnection?.addIceCandidate(cand)
                    }
                    pendingIceCandidates.clear()
                }
                onComplete()
            }
            override fun onCreateFailure(p0: String?) { onError(p0 ?: "Failed to set remote description") }
            override fun onSetFailure(p0: String?) { onError(p0 ?: "Failed to set remote description") }
        }, desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (isRemoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            synchronized(pendingIceCandidates) {
                pendingIceCandidates.add(candidate)
            }
        }
    }

    fun isControlChannelOpen(): Boolean {
        return controlDataChannel?.state() == DataChannel.State.OPEN
    }

    fun getEglContext(): EglBase.Context = eglBase.eglBaseContext

    fun isScreenCaptureActive(): Boolean = screenCaptureActive

    /** Starts sharing only with a system-issued MediaProjection consent token. */
    @Synchronized
    fun startScreenCapture(resultCode: Int, resultData: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            listener.onScreenCaptureStateChanged(false, false, "Bildschirmfreigabe wurde nicht von Android genehmigt.")
            return false
        }
        if (screenCaptureActive) {
            listener.onScreenCaptureStateChanged(true, false, null)
            return true
        }
        pendingScreenPermission = resultCode to resultData
        listener.onScreenCaptureStateChanged(false, true, "Freigabe erteilt; Bildschirmübertragung wird vorbereitet.")
        return if (screenVideoSource != null && surfaceTextureHelper != null) {
            startPendingScreenCaptureIfReady()
        } else {
            true
        }
    }

    @Synchronized
    fun stopScreenCapture(reason: String = "Bildschirmfreigabe beendet.") {
        pendingScreenPermission = null
        val capturer = screenCapturer
        screenCapturer = null
        screenCaptureActive = false
        localScreenTrack?.setEnabled(false)
        if (capturer != null) {
            try {
                capturer.stopCapture()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                listener.onError("Bildschirmaufnahme wurde beim Stoppen unterbrochen.")
            } catch (_: RuntimeException) {
                // Projection may already have been stopped by Android's system UI.
            } finally {
                // Dispose the capturer as well as stopping it so its MediaProjection callback
                // and surface references cannot leak after the user ends screen sharing.
                runCatching { capturer.dispose() }
            }
        }
        listener.onScreenCaptureStateChanged(false, false, reason)
    }

    private fun prepareLocalScreenTrack(pc: PeerConnection) {
        if (screenVideoSource == null) {
            screenVideoSource = peerConnectionFactory.createVideoSource(true)
            surfaceTextureHelper = SurfaceTextureHelper.create("RemoteScreenCapture", eglBase.eglBaseContext)
        }
        val source = screenVideoSource ?: throw IllegalStateException("VideoSource fehlt")
        val track = localScreenTrack ?: peerConnectionFactory.createVideoTrack("remote-screen-track", source).also {
            localScreenTrack = it
            it.setEnabled(false)
        }
        val sender = pc.addTrack(track, listOf("remote-screen-stream"))
        if (sender == null) throw IllegalStateException("WebRTC hat den Bildschirm-Track abgelehnt")
        track.setEnabled(screenCaptureActive)
    }

    @Synchronized
    private fun startPendingScreenCaptureIfReady(): Boolean {
        if (screenCaptureActive) return true
        val grant = pendingScreenPermission ?: return true
        val source = screenVideoSource ?: return true
        val helper = surfaceTextureHelper ?: return true
        if (grant.first != Activity.RESULT_OK) {
            pendingScreenPermission = null
            listener.onScreenCaptureStateChanged(false, false, "Ungültige Android-Bildschirmfreigabe.")
            return false
        }

        var createdCapturer: ScreenCapturerAndroid? = null
        return try {
            val newCapturer = ScreenCapturerAndroid(grant.second, object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    // Projection callbacks can arrive on WebRTC's helper thread. Marshal teardown
                    // to the main thread and release the native capturer deterministically.
                    Handler(Looper.getMainLooper()).post {
                        val endedCapturer = screenCapturer
                        screenCapturer = null
                        screenCaptureActive = false
                        localScreenTrack?.setEnabled(false)
                        pendingScreenPermission = null
                        runCatching { endedCapturer?.stopCapture() }
                        runCatching { endedCapturer?.dispose() }
                        listener.onScreenCaptureStateChanged(false, false, "Android hat die Bildschirmfreigabe beendet.")
                    }
                }
            })
            createdCapturer = newCapturer
            newCapturer.initialize(helper, context, source.capturerObserver)
            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: throw IllegalStateException("DisplayManager nicht verfügbar")
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val naturalWidth = metrics.widthPixels.coerceAtLeast(2)
            val naturalHeight = metrics.heightPixels.coerceAtLeast(2)
            // Bound resolution to reduce thermal/bandwidth pressure while preserving aspect ratio.
            val scale = minOf(1f, 1920f / maxOf(naturalWidth, naturalHeight).toFloat())
            val captureWidth = ((naturalWidth * scale).toInt().coerceAtLeast(2) / 2) * 2
            val captureHeight = ((naturalHeight * scale).toInt().coerceAtLeast(2) / 2) * 2
            screenCapturer = newCapturer
            newCapturer.startCapture(captureWidth, captureHeight, 15)
            screenCaptureActive = true
            pendingScreenPermission = null
            localScreenTrack?.setEnabled(true)
            listener.onScreenCaptureStateChanged(true, false, "Bildschirmfreigabe läuft (${captureWidth}×${captureHeight}, 15 FPS).")
            true
        } catch (e: Exception) {
            pendingScreenPermission = null
            screenCaptureActive = false
            localScreenTrack?.setEnabled(false)
            screenCapturer = null
            runCatching { createdCapturer?.stopCapture() }
            runCatching { createdCapturer?.dispose() }
            listener.onScreenCaptureStateChanged(false, false, "Bildschirmaufnahme konnte nicht starten: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun resetPeerConnection() {
        controlDataChannel?.close()
        audioDataChannel?.close()
        controlDataChannel = null
        audioDataChannel = null
        peerConnection?.let { oldPeer ->
            runCatching { oldPeer.close() }
            runCatching { oldPeer.dispose() }
        }
        peerConnection = null
        isRemoteDescriptionSet = false
        pendingIceCandidates.clear()
        listener.onRemoteVideoTrack(null)
    }

    /** Closes the peer but keeps the factory/video source reusable if pairing starts again. */
    fun close() {
        resetPeerConnection()
        stopScreenCapture("Bildschirmfreigabe wurde getrennt.")
    }

    /** Final lifecycle cleanup; called when the owning ViewModel is discarded. */
    fun dispose() {
        close()
        try { localScreenTrack?.dispose() } catch (_: Exception) {}
        localScreenTrack = null
        try { screenVideoSource?.dispose() } catch (_: Exception) {}
        screenVideoSource = null
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        surfaceTextureHelper = null
        try { peerConnectionFactory.dispose() } catch (_: Exception) {}
        try { eglBase.release() } catch (_: Exception) {}
    }
}
