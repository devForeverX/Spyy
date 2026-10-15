package com.remote.androidcontroller.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.WindowManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remote.androidcontroller.audio.AudioFlowState
import com.remote.androidcontroller.audio.AudioStreamEngine
import com.remote.androidcontroller.crypto.PairedDevice
import com.remote.androidcontroller.crypto.PairingCrypto
import com.remote.androidcontroller.crypto.PairingManager
import com.remote.androidcontroller.network.ActionResultPayload
import com.remote.androidcontroller.network.AudioFlowControlPayload
import com.remote.androidcontroller.network.AudioStatusPayload
import com.remote.androidcontroller.network.AudioVolumePayload
import com.remote.androidcontroller.network.BaseMessage
import com.remote.androidcontroller.network.BrightnessSetPayload
import com.remote.androidcontroller.network.ClipboardTransferPayload
import com.remote.androidcontroller.network.ControlActionPayload
import com.remote.androidcontroller.network.DeviceStatusPayload
import com.remote.androidcontroller.network.FlashlightSetPayload
import com.remote.androidcontroller.network.IceCandidatePayload
import com.remote.androidcontroller.network.IceServersPayload
import com.remote.androidcontroller.network.MessageType
import com.remote.androidcontroller.network.NavigationAction
import com.remote.androidcontroller.network.PairingConfirmPayload
import com.remote.androidcontroller.network.PairingHandshakeInitPayload
import com.remote.androidcontroller.network.PairingInitPayload
import com.remote.androidcontroller.network.PairingResponsePayload
import com.remote.androidcontroller.network.PairingSubmitPayload
import com.remote.androidcontroller.network.PermissionStatusPayload
import com.remote.androidcontroller.network.ProtocolJson
import com.remote.androidcontroller.network.RemoteTouchAction
import com.remote.androidcontroller.network.RemoteTouchPayload
import com.remote.androidcontroller.network.ScreenShareStatusPayload
import com.remote.androidcontroller.network.RegisterPayload
import com.remote.androidcontroller.network.RegisterAckPayload
import com.remote.androidcontroller.network.RegisterAuthPayload
import com.remote.androidcontroller.network.SdpPayload
import com.remote.androidcontroller.network.SignalingClient
import com.remote.androidcontroller.network.SignalingListener
import com.remote.androidcontroller.network.TextInputPayload
import com.remote.androidcontroller.network.VibrationPattern
import com.remote.androidcontroller.network.VibrationTestPayload
import com.remote.androidcontroller.network.WebRtcListener
import com.remote.androidcontroller.network.WebRtcPeerManager
import com.remote.androidcontroller.services.HardwareController
import com.remote.androidcontroller.services.RemoteControlAccessibilityService
import com.remote.androidcontroller.services.RemoteControlSessionService
import com.remote.androidcontroller.services.ScreenCaptureService
import com.remote.androidcontroller.services.RemoteInputMethodService
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.security.KeyPair
import android.media.MediaMetadataRetriever
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.sin

enum class AppRole {
    UNSELECTED,
    ADMIN,
    REMOTE
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING_SIGNALING,
    SIGNALING_ONLINE,
    PAIRED_CONNECTING_PEER,
    CONNECTED_TRANSPORT_UNKNOWN,
    CONNECTED_P2P,
    CONNECTED_RELAY
}

data class RemoteControlUiState(
    val role: AppRole = AppRole.UNSELECTED,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val serverUrl: String = "", // Must be configured for the deployed signaling server
    val isPaired: Boolean = false,
    val pairingId: String? = null,
    val pairedDeviceName: String? = null,
    val currentPairingCode: String? = null,
    val sasVerificationCode: String? = null,
    val isLockedOut: Boolean = false,
    val lockoutRemainingSeconds: Long = 0L,
    val measuredRttMs: Long = 0L,
    val lastActionResultMessage: String? = null,
    val deviceStatus: DeviceStatusPayload? = null,
    val audioStatus: AudioStatusPayload? = null,
    val permissionStatus: PermissionStatusPayload? = null,
    val isControlChannelOpen: Boolean = false,
    val isPairingHandshakeInProgress: Boolean = false,
    val isScreenSharing: Boolean = false,
    val isScreenSharePending: Boolean = false,
    val remoteScreenShareActive: Boolean = false,
    val remoteScreenWidth: Int = 0,
    val remoteScreenHeight: Int = 0,
    val remoteVideoTrack: VideoTrack? = null
)

class RemoteControlViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val settings = context.getSharedPreferences("remote_control_settings", android.content.Context.MODE_PRIVATE)
    // Stable per-install identity; unlike manufacturer/model, this is unique across phones of the same model.
    // The cryptographic Android Keystore identity key remains the authentication credential.
    private val localDeviceId: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        settings.getString("device_id", null)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also { id ->
            settings.edit().putString("device_id", id).apply()
        }
    }
    private val pairingManager = PairingManager(context)
    private val hardwareController = HardwareController(context)

    private val _uiState = MutableStateFlow(
        RemoteControlUiState(
            serverUrl = settings.getString("server_url", "mongodb://cloud").orEmpty().ifBlank { "mongodb://cloud" }
        )
    )
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()

    private var signalingClient: SignalingClient? = null
    private var webRtcManager: WebRtcPeerManager? = null
    private var audioStreamEngine: AudioStreamEngine? = null
    private var selectedAudioUri: Uri? = null
    private var lastIceServers: List<PeerConnection.IceServer> = emptyList()
    private var peerRecoveryJob: Job? = null
    private var peerRecoveryGeneration = 0L
    private var peerSignalingAvailable = false
    private var offerStartedForCurrentPc = false

    // Ephemeral cryptographic state during ongoing pairing handshake
    private var currentEphemeralKeyPair: KeyPair? = null
    private var currentNonce: ByteArray? = null
    private var currentPeerEphemeralPublicKey: ByteArray? = null
    private var currentPeerIdentityKeyBase64: String? = null
    private var currentPeerNonce: ByteArray? = null
    private var activeSharedSecret: ByteArray? = null
    private var activePairingId: String? = null
    private var activeDerivedAuthKey: ByteArray? = null
    private var activeDerivedConfirmKey: ByteArray? = null
    private var localConfirmationSent = false
    private var peerConfirmationVerified = false
    private var localCommitSent = false

    private var deviceStatusBroadcastJob: Job? = null
    private var monotonicSequence = 0L

    // A bounded duplicate cache plus a sliding sequence window defends the control channel
    // against replayed/stale commands without rejecting a small amount of concurrent reordering.
    private val incomingControlGuardLock = Any()
    private val recentControlMessageIds = LinkedHashSet<String>()
    private val recentControlSequences = LinkedHashSet<Long>()
    private var highestIncomingControlSequence = 0L
    private val maxRememberedControlMessages = 1_024
    private val controlSequenceWindow = 64L

    init {
        initializeAudioStreamEngine()
        checkPersistedPairing()
        checkLocalPermissions()
    }

    private fun initializeAudioStreamEngine() {
        audioStreamEngine = AudioStreamEngine(
            context = context,
            onSendBinaryPacket = { packet -> webRtcManager?.sendAudioBinary(packet) ?: false },
            onSendFlowControl = { flowState ->
                val control = ProtocolJson.createMessage(
                    MessageType.AUDIO_FLOW_CONTROL,
                    AudioFlowControlPayload(flowState),
                    _uiState.value.pairingId
                )
                webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(control))
            },
            onAudioStatusChanged = { playing, paused, positionMs ->
                val currentEngine = audioStreamEngine
                val status = AudioStatusPayload(
                    isPlaying = playing,
                    isPaused = paused,
                    positionMs = positionMs,
                    durationMs = _uiState.value.audioStatus?.durationMs ?: 0L,
                    bufferFillPercent = currentEngine?.getBufferFillPercent() ?: 0,
                    underrunCount = currentEngine?.getUnderrunCount() ?: 0
                )
                _uiState.update { it.copy(audioStatus = status) }
                // The target reports real playback status to the controller; the controller does not echo it.
                if (_uiState.value.role == AppRole.REMOTE && _uiState.value.isControlChannelOpen) {
                    val message = ProtocolJson.createMessage(MessageType.AUDIO_STATUS, status, _uiState.value.pairingId)
                    webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(message))
                }
            },
            onError = { reason ->
                _uiState.update { it.copy(lastActionResultMessage = reason) }
                // Report receiver-side playback failures back to the controller; a selected file or
                // a successful sender-side decode does not prove that the target can play audio.
                if (_uiState.value.role == AppRole.REMOTE && _uiState.value.isControlChannelOpen) {
                    val failure = ProtocolJson.createMessage(
                        MessageType.ACTION_RESULT,
                        ActionResultPayload(
                            targetMessageId = "audio-stream",
                            success = false,
                            message = reason
                        ),
                        _uiState.value.pairingId
                    )
                    webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(failure))
                }
            }
        )
    }

    private fun checkPersistedPairing() {
        val paired = pairingManager.getPairedDevice()
        if (paired != null) {
            _uiState.update {
                it.copy(
                    isPaired = true,
                    pairingId = paired.pairingId,
                    pairedDeviceName = paired.peerDeviceName
                )
            }
        }
    }

    fun selectRole(role: AppRole) {
        val serverUrl = _uiState.value.serverUrl.trim()
        if (!isAllowedSignalingUrl(serverUrl)) {
            _uiState.update {
                it.copy(lastActionResultMessage = "Bitte eine gültige Signalisierungsadresse eintragen. Für einen öffentlichen Server wird wss:// mit gültigem TLS-Zertifikat benötigt.")
            }
            return
        }
        settings.edit().putString("server_url", serverUrl).apply()
        _uiState.update { it.copy(role = role, lastActionResultMessage = null) }
        initializeNetwork(serverUrl)
        if (role == AppRole.REMOTE) {
            generateNewPairingCode()
        }
    }

    /** Updates and persists configuration only; editing each character must not reconnect the socket. */
    fun setServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url) }
        settings.edit().putString("server_url", url.trim()).apply()
    }

    private fun isAllowedSignalingUrl(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("mongodb", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return true
        }
        val uri = android.net.Uri.parse(trimmed)
        val host = uri.host?.lowercase() ?: return false
        if (uri.port == 0 || uri.port > 65535) return false
        if (uri.scheme.equals("wss", ignoreCase = true)) return true
        if (!uri.scheme.equals("ws", ignoreCase = true)) return false
        // Cleartext signaling is allowed only for local development endpoints.
        return host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            (host.startsWith("172.") && (host.substringAfter("172.").substringBefore('.').toIntOrNull() ?: 0) in 16..31)
    }

    fun initializeNetwork(serverUrl: String) {
        signalingClient?.disconnect()
        webRtcManager?.dispose()
        webRtcManager = null
        peerSignalingAvailable = false
        offerStartedForCurrentPc = false
        lastIceServers = emptyList()

        _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTING_SIGNALING) }

        // Setup WebRTC Peer Manager
        webRtcManager = WebRtcPeerManager(context, object : WebRtcListener {
            override fun onIceCandidateGenerated(candidate: IceCandidate) {
                val payload = IceCandidatePayload(
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    sdp = candidate.sdp
                )
                val msg = ProtocolJson.createMessage(
                    type = MessageType.ICE_CANDIDATE,
                    payload = payload,
                    pairingId = _uiState.value.pairingId
                )
                signalingClient?.sendMessage(msg)
            }

            override fun onConnectionStateChange(newState: PeerConnection.PeerConnectionState) {
                viewModelScope.launch(Dispatchers.Main) {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            peerRecoveryGeneration++
                            peerRecoveryJob?.cancel()
                            peerRecoveryJob = null
                            _uiState.update { current ->
                                if (current.connectionStatus == ConnectionStatus.CONNECTED_P2P ||
                                    current.connectionStatus == ConnectionStatus.CONNECTED_RELAY) current
                                else current.copy(connectionStatus = ConnectionStatus.CONNECTED_TRANSPORT_UNKNOWN)
                            }
                            RemoteControlSessionService.startService(
                                context,
                                _uiState.value.pairedDeviceName ?: "Gekoppeltes Gerät"
                            )
                            if (_uiState.value.role == AppRole.REMOTE) {
                                startDeviceStatusBroadcasting()
                            }
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED -> {
                            _uiState.update { current ->
                                current.copy(
                                    connectionStatus = ConnectionStatus.SIGNALING_ONLINE,
                                    isControlChannelOpen = false,
                                    measuredRttMs = 0L,
                                    remoteVideoTrack = null,
                                    remoteScreenShareActive = false,
                                    remoteScreenWidth = 0,
                                    remoteScreenHeight = 0
                                )
                            }
                            RemoteControlSessionService.stopService(context)
                            stopDeviceStatusBroadcasting()
                            schedulePeerConnectionRecovery()
                        }
                        else -> {}
                    }
                }
            }

            override fun onDataChannelStateChange(channelLabel: String, isOpen: Boolean) {
                if (channelLabel == "control") {
                    _uiState.update { it.copy(isControlChannelOpen = isOpen) }
                    if (isOpen) {
                        synchronized(incomingControlGuardLock) {
                            // A new WebRTC data channel may follow an app process restart, where
                            // the sender's sequence counter starts afresh. Keep message IDs cached.
                            highestIncomingControlSequence = 0L
                            recentControlSequences.clear()
                        }
                    }
                    if (isOpen && _uiState.value.role == AppRole.REMOTE) {
                        sendScreenShareStatus(_uiState.value.isScreenSharing, null)
                    }
                }
            }

            override fun onControlMessageReceived(messageJson: String) {
                handleIncomingControlMessage(messageJson)
            }

            override fun onAudioBinaryReceived(bytes: ByteArray) {
                audioStreamEngine?.handleIncomingBinaryPacket(bytes)
            }

            override fun onRemoteVideoTrack(track: VideoTrack?) {
                // Only the controller renders a remote screen. The target's negotiated receive
                // track is not proof of screen sharing and should not be retained as UI state.
                if (_uiState.value.role == AppRole.ADMIN) {
                    _uiState.update { it.copy(remoteVideoTrack = track) }
                }
            }

            override fun onScreenCaptureStateChanged(active: Boolean, pending: Boolean, message: String?) {
                _uiState.update {
                    it.copy(
                        isScreenSharing = active,
                        isScreenSharePending = pending,
                        lastActionResultMessage = message ?: it.lastActionResultMessage
                    )
                }
                if (!pending) sendScreenShareStatus(active, message)
                if (!active && message == "Android hat die Bildschirmfreigabe beendet.") {
                    // Android ended the projection outside our UI (e.g. system notification/shade).
                    runCatching { ScreenCaptureService.stop(context) }
                }
            }

            override fun onRttMeasured(rttMs: Long, isRelayed: Boolean) {
                _uiState.update {
                    it.copy(
                        measuredRttMs = rttMs,
                        connectionStatus = if (isRelayed) ConnectionStatus.CONNECTED_RELAY else ConnectionStatus.CONNECTED_P2P
                    )
                }
            }

            override fun onError(reason: String) {
                _uiState.update { it.copy(lastActionResultMessage = "WebRTC Fehler: $reason") }
            }
        })

        val roleStr = if (_uiState.value.role == AppRole.ADMIN) "admin" else "remote"
        // Setup Signaling Client
        signalingClient = SignalingClient(
            serverUrl = serverUrl,
            listener = object : SignalingListener {
                override fun onConnected() {
                    _uiState.update { it.copy(connectionStatus = ConnectionStatus.SIGNALING_ONLINE) }

                    // Register authenticated device
                    val regPayload = RegisterPayload(
                        deviceId = localDeviceId,
                        role = roleStr,
                        identityPublicKeyBase64 = pairingManager.getPublicIdentityKeyBase64(),
                    savedPairingId = _uiState.value.pairingId
                )
                val msg = ProtocolJson.createMessage(MessageType.REGISTER, regPayload, _uiState.value.pairingId)
                signalingClient?.sendMessage(msg)
            }

            override fun onDisconnected() {
                peerSignalingAvailable = false
                offerStartedForCurrentPc = false
                if (!_uiState.value.isPaired) {
                    // A PIN is registered against this WebSocket session only. Never leave a stale
                    // code visible after a disconnect; ICE_SERVERS will trigger a new random PIN.
                    resetPendingPairingState()
                    _uiState.update { it.copy(currentPairingCode = null, sasVerificationCode = null) }
                }
                _uiState.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        isControlChannelOpen = false,
                        measuredRttMs = 0L,
                        remoteVideoTrack = null,
                        remoteScreenShareActive = false
                    )
                }
                RemoteControlSessionService.stopService(context)
                stopDeviceStatusBroadcasting()
            }

            override fun onMessageReceived(message: BaseMessage) {
                handleIncomingSignalingMessage(message)
            }

            override fun onError(reason: String) {
                _uiState.update { it.copy(lastActionResultMessage = "Signalisierungsfehler: $reason") }
            }
        },
        localDeviceId = localDeviceId,
        role = roleStr
    )

        signalingClient?.connect()
    }

    private fun handleIncomingSignalingMessage(msg: BaseMessage) {
        when (msg.type) {
            MessageType.REGISTER_ACK -> {
                val acknowledgement = runCatching {
                    ProtocolJson.decodePayload<RegisterAckPayload>(msg.payload)
                }.getOrNull() ?: return
                if (acknowledgement.status == "AUTH_REQUIRED") {
                    val pairedDevice = pairingManager.getPairedDevice() ?: return
                    val challengeBase64 = acknowledgement.challengeBase64 ?: return
                    val challenge = try {
                        java.util.Base64.getDecoder().decode(challengeBase64)
                    } catch (_: IllegalArgumentException) {
                        return
                    }
                    try {
                        val signature = pairingManager.signIdentityChallenge(challenge)
                        val role = if (_uiState.value.role == AppRole.ADMIN) "admin" else "remote"
                        val payload = RegisterAuthPayload(
                            pairingId = pairedDevice.pairingId,
                            deviceId = localDeviceId,
                            role = role,
                            identityPublicKeyBase64 = pairingManager.getPublicIdentityKeyBase64(),
                            challengeBase64 = challengeBase64,
                            signatureBase64 = java.util.Base64.getEncoder().encodeToString(signature)
                        )
                        signalingClient?.sendMessage(
                            ProtocolJson.createMessage(MessageType.REGISTER_AUTH, payload, pairedDevice.pairingId)
                        )
                    } catch (e: Exception) {
                        _uiState.update { it.copy(lastActionResultMessage = "Geräteidentität konnte nicht authentifiziert werden: ${e.message}") }
                    }
                }
            }

            MessageType.ICE_SERVERS -> {
                // Use the short-lived TURN credentials supplied by the configured server.
                val serverConfig = runCatching {
                    ProtocolJson.decodePayload<IceServersPayload>(msg.payload)
                }.getOrNull()
                val configuredServers = serverConfig?.iceServers.orEmpty().mapNotNull { entry ->
                    if (entry.urls.isEmpty()) return@mapNotNull null
                    val builder = PeerConnection.IceServer.builder(entry.urls)
                    if (!entry.username.isNullOrBlank()) builder.setUsername(entry.username)
                    if (!entry.credential.isNullOrBlank()) builder.setPassword(entry.credential)
                    builder.createIceServer()
                }
                val iceServers = configuredServers.ifEmpty {
                    // STUN-only fallback is useful on permissive networks but does not guarantee relaying.
                    listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
                }
                lastIceServers = iceServers
                offerStartedForCurrentPc = false
                webRtcManager?.initializePeerConnection(iceServers)

                // Wait until the server confirms that the paired peer is online before offering.
                if (_uiState.value.role == AppRole.ADMIN && _uiState.value.isPaired && peerSignalingAvailable) {
                    startWebRtcCall()
                } else if (_uiState.value.role == AppRole.REMOTE && !_uiState.value.isPaired &&
                    _uiState.value.currentPairingCode == null) {
                    // Generate the PIN only after the signaling connection and server registration exist.
                    generateNewPairingCode()
                }
            }

            MessageType.PEER_ONLINE -> {
                peerSignalingAvailable = true
                if (_uiState.value.role == AppRole.ADMIN && _uiState.value.isPaired && lastIceServers.isNotEmpty()) {
                    // If the peer came back while signaling stayed open, the old data channel may be dead
                    // even though the WebSocket is alive. Replace that peer connection before re-offering.
                    if (_uiState.value.connectionStatus == ConnectionStatus.SIGNALING_ONLINE &&
                        !_uiState.value.isControlChannelOpen) {
                        offerStartedForCurrentPc = false
                        webRtcManager?.initializePeerConnection(lastIceServers)
                    }
                    startWebRtcCall()
                }
            }

            MessageType.DISCONNECT -> {
                peerSignalingAvailable = false
                _uiState.update {
                    it.copy(
                        connectionStatus = if (it.isPaired) ConnectionStatus.SIGNALING_ONLINE else it.connectionStatus,
                        isControlChannelOpen = false,
                        measuredRttMs = 0L,
                        lastActionResultMessage = "Gekoppeltes Gerät hat die Signalisierungsverbindung getrennt. Warte auf Wiederverbindung."
                    )
                }
                RemoteControlSessionService.stopService(context)
                stopDeviceStatusBroadcasting()
            }

            MessageType.PAIRING_INIT_ACK -> {
                _uiState.update { it.copy(lastActionResultMessage = "Pairing-Code auf Server aktiv (5 Min. gültig)") }
            }

            MessageType.ERROR -> {
                val error = runCatching { ProtocolJson.decodePayload<com.remote.androidcontroller.network.ErrorPayload>(msg.payload) }.getOrNull()
                if (error != null) {
                    _uiState.update { it.copy(lastActionResultMessage = error.errorMessage) }
                    if (error.errorCode == "INVALID_CODE") {
                        pairingManager.recordFailedAttempt()
                        val remaining = pairingManager.getRemainingLockoutSeconds()
                        _uiState.update { it.copy(isLockedOut = remaining > 0L, lockoutRemainingSeconds = remaining) }
                    }
                    if (error.errorCode == "UNKNOWN_PAIRING" || error.errorCode == "IDENTITY_MISMATCH") {
                        pairingManager.revokePairing()
                        _uiState.update {
                            it.copy(isPaired = false, pairingId = null, pairedDeviceName = null, sasVerificationCode = null)
                        }
                    }
                }
            }

            MessageType.PAIRING_RESPONSE -> {
                // Controller received the target device's ephemeral key and authentication identity.
                val payload = ProtocolJson.decodePayload<PairingResponsePayload>(msg.payload)
                val myKeyPair = currentEphemeralKeyPair ?: return
                val myNonce = currentNonce ?: return

                val remotePubKey = PairingCrypto.decodePublicKey(payload.ephemeralPublicKeyBase64)
                val sharedSecret = PairingCrypto.computeSharedSecret(myKeyPair.private, remotePubKey)
                val decodedNonce = java.util.Base64.getDecoder().decode(payload.nonceBase64)

                currentPeerEphemeralPublicKey = remotePubKey.encoded
                currentPeerIdentityKeyBase64 = payload.identityPublicKeyBase64
                currentPeerNonce = decodedNonce
                activeSharedSecret = sharedSecret
                activePairingId = PairingCrypto.computePairingId(
                    pairingManager.getPublicIdentityKeyBase64(),
                    payload.identityPublicKeyBase64
                )

                val (authKey, confirmKey) = PairingCrypto.deriveHandshakeKeys(
                    sharedSecret = sharedSecret,
                    nonceA = myNonce,
                    nonceB = decodedNonce,
                    pairingCode = payload.code
                )
                activeDerivedAuthKey = authKey
                activeDerivedConfirmKey = confirmKey

                val sas = PairingCrypto.calculateSasCode(confirmKey)
                _uiState.update {
                    it.copy(
                        sasVerificationCode = sas,
                        isPairingHandshakeInProgress = true,
                        pairedDeviceName = payload.remoteDeviceName,
                        lastActionResultMessage = "Vergleiche den SAS-Code auf beiden Geräten."
                    )
                }
            }

            MessageType.PAIRING_HANDSHAKE_INIT -> {
                // Target received the controller's ephemeral key and authentication identity.
                val payload = ProtocolJson.decodePayload<PairingHandshakeInitPayload>(msg.payload)
                val myKeyPair = currentEphemeralKeyPair ?: return
                val myNonce = currentNonce ?: return

                val adminPubKey = PairingCrypto.decodePublicKey(payload.ephemeralPublicKeyBase64)
                val sharedSecret = PairingCrypto.computeSharedSecret(myKeyPair.private, adminPubKey)

                val adminNonce = java.util.Base64.getDecoder().decode(payload.nonceBase64)
                currentPeerEphemeralPublicKey = adminPubKey.encoded
                currentPeerIdentityKeyBase64 = payload.identityPublicKeyBase64
                currentPeerNonce = adminNonce
                activeSharedSecret = sharedSecret
                activePairingId = PairingCrypto.computePairingId(
                    pairingManager.getPublicIdentityKeyBase64(),
                    payload.identityPublicKeyBase64
                )

                val (authKey, confirmKey) = PairingCrypto.deriveHandshakeKeys(
                    sharedSecret = sharedSecret,
                    nonceA = adminNonce,
                    nonceB = myNonce,
                    pairingCode = payload.code
                )
                activeDerivedAuthKey = authKey
                activeDerivedConfirmKey = confirmKey

                val sas = PairingCrypto.calculateSasCode(confirmKey)
                _uiState.update {
                    it.copy(
                        sasVerificationCode = sas,
                        isPairingHandshakeInProgress = true,
                        pairedDeviceName = "Fernbedienung",
                        lastActionResultMessage = "Vergleiche den SAS-Code auf beiden Geräten."
                    )
                }
            }

            MessageType.PAIRING_CONFIRM -> {
                val payload = ProtocolJson.decodePayload<PairingConfirmPayload>(msg.payload)
                val authKey = activeDerivedAuthKey
                val localPublicKey = currentEphemeralKeyPair?.public?.encoded
                val peerPublicKey = currentPeerEphemeralPublicKey
                val localNonce = currentNonce
                val peerNonce = currentPeerNonce
                val expectedPairingId = activePairingId

                if (authKey == null || localPublicKey == null || peerPublicKey == null ||
                    localNonce == null || peerNonce == null || expectedPairingId == null ||
                    payload.pairingId != expectedPairingId || msg.pairingId != expectedPairingId
                ) {
                    _uiState.update { it.copy(lastActionResultMessage = "Pairing abgebrochen: ungültige Bestätigung.") }
                    return
                }

                val expectedProof = PairingCrypto.computeAuthProof(
                    authKey = authKey,
                    label = "SAS_CONFIRM",
                    myPub = peerPublicKey,
                    peerPub = localPublicKey,
                    nonceA = peerNonce,
                    nonceB = localNonce
                )
                val receivedProof = try {
                    java.util.Base64.getDecoder().decode(payload.authProofBase64)
                } catch (_: IllegalArgumentException) {
                    byteArrayOf()
                }

                if (!MessageDigest.isEqual(expectedProof, receivedProof)) {
                    _uiState.update { it.copy(lastActionResultMessage = "Sicherheitsprüfung fehlgeschlagen. Gerät nicht gekoppelt.") }
                    return
                }

                peerConfirmationVerified = true
                _uiState.update { it.copy(lastActionResultMessage = "Gegenstelle kryptografisch bestätigt.") }
                maybeCommitPairing()
            }

            MessageType.PAIRING_SUCCESS -> {
                val payload = ProtocolJson.decodePayload<com.remote.androidcontroller.network.PairingSuccessPayload>(msg.payload)
                if (!localConfirmationSent || !peerConfirmationVerified || !localCommitSent || payload.pairingId != activePairingId) {
                    _uiState.update {
                        it.copy(lastActionResultMessage = "Pairing-Erfolg vom Server ignoriert: beidseitige kryptografische Bestätigung fehlt.")
                    }
                    return
                }
                pairingManager.recordSuccess()

                // Save persistent trust binding
                val paired = PairedDevice(
                    pairingId = payload.pairingId,
                    peerPublicKeyBase64 = currentPeerIdentityKeyBase64 ?: "",
                    peerDeviceName = _uiState.value.pairedDeviceName ?: "Gekoppeltes Smartphone",
                    sharedSecretBase64 = java.util.Base64.getEncoder().encodeToString(activeDerivedAuthKey ?: activeSharedSecret ?: byteArrayOf()),
                    pairedAtTimestamp = payload.pairedAt
                )
                pairingManager.savePairedDevice(paired)

                _uiState.update {
                    it.copy(
                        isPaired = true,
                        pairingId = payload.pairingId,
                        currentPairingCode = null,
                        sasVerificationCode = null,
                        isPairingHandshakeInProgress = false,
                        lastActionResultMessage = "Erfolgreich gekoppelt!"
                    )
                }

                // A commit requires both live sockets; the first offer can start immediately.
                peerSignalingAvailable = true
                if (_uiState.value.role == AppRole.ADMIN) startWebRtcCall()
            }

            MessageType.SDP_OFFER -> {
                val sdpPayload = ProtocolJson.decodePayload<SdpPayload>(msg.payload)
                val desc = SessionDescription(SessionDescription.Type.OFFER, sdpPayload.sdp)
                webRtcManager?.setRemoteDescription(desc, onComplete = {
                    webRtcManager?.createAnswer(
                        onSuccess = { answerDesc ->
                            val answerMsg = ProtocolJson.createMessage(
                                type = MessageType.SDP_ANSWER,
                                payload = SdpPayload("answer", answerDesc.description),
                                pairingId = _uiState.value.pairingId
                            )
                            signalingClient?.sendMessage(answerMsg)
                        },
                        onError = { err ->
                            _uiState.update { it.copy(lastActionResultMessage = "Answer Error: $err") }
                        }
                    )
                }, onError = { err ->
                    _uiState.update { it.copy(lastActionResultMessage = "Remote Description Error: $err") }
                })
            }

            MessageType.SDP_ANSWER -> {
                val sdpPayload = ProtocolJson.decodePayload<SdpPayload>(msg.payload)
                val desc = SessionDescription(SessionDescription.Type.ANSWER, sdpPayload.sdp)
                webRtcManager?.setRemoteDescription(desc, onComplete = {
                    // Ready
                }, onError = { err ->
                    _uiState.update { it.copy(lastActionResultMessage = "Set Answer Error: $err") }
                })
            }

            MessageType.ICE_CANDIDATE -> {
                val candPayload = ProtocolJson.decodePayload<IceCandidatePayload>(msg.payload)
                val candidate = IceCandidate(candPayload.sdpMid, candPayload.sdpMLineIndex, candPayload.sdp)
                webRtcManager?.addIceCandidate(candidate)
            }

            MessageType.PAIRING_REVOKED -> {
                pairingManager.revokePairing()
                _uiState.update {
                    it.copy(
                        isPaired = false,
                        pairingId = null,
                        pairedDeviceName = null,
                        sasVerificationCode = null,
                        deviceStatus = null,
                        connectionStatus = ConnectionStatus.SIGNALING_ONLINE,
                        lastActionResultMessage = "Kopplung wurde widerrufen."
                    )
                }
                webRtcManager?.close()
            }

            else -> {}
        }
    }

    private fun startWebRtcCall() {
        if (_uiState.value.role != AppRole.ADMIN || !_uiState.value.isPaired ||
            !peerSignalingAvailable || offerStartedForCurrentPc || webRtcManager == null) return
        offerStartedForCurrentPc = true
        _uiState.update { it.copy(connectionStatus = ConnectionStatus.PAIRED_CONNECTING_PEER) }
        runCatching {
            webRtcManager?.createDataChannels()
            webRtcManager?.createOffer(
                onSuccess = { offerDesc ->
                    val offerMsg = ProtocolJson.createMessage(
                        type = MessageType.SDP_OFFER,
                        payload = SdpPayload("offer", offerDesc.description),
                        pairingId = _uiState.value.pairingId
                    )
                    signalingClient?.sendMessage(offerMsg)
                },
                onError = { err ->
                    offerStartedForCurrentPc = false
                    _uiState.update { it.copy(lastActionResultMessage = "Offer Status: $err") }
                }
            )
        }.onFailure { err ->
            offerStartedForCurrentPc = false
            _uiState.update { it.copy(lastActionResultMessage = "P2P Initialisierung: ${err.message}") }
        }
    }

    private fun schedulePeerConnectionRecovery() {
        if (!_uiState.value.isPaired || lastIceServers.isEmpty() || !peerSignalingAvailable) return
        peerRecoveryJob?.cancel()
        val recoveryToken = ++peerRecoveryGeneration
        peerRecoveryJob = viewModelScope.launch {
            delay(4_000)
            if (recoveryToken != peerRecoveryGeneration || !_uiState.value.isPaired ||
                _uiState.value.isControlChannelOpen || !peerSignalingAvailable) return@launch
            offerStartedForCurrentPc = false
            _uiState.update { it.copy(connectionStatus = ConnectionStatus.PAIRED_CONNECTING_PEER) }
            webRtcManager?.initializePeerConnection(lastIceServers)
            if (_uiState.value.role == AppRole.ADMIN) {
                delay(300)
                if (recoveryToken == peerRecoveryGeneration) startWebRtcCall()
            }
        }
    }

    fun generateNewPairingCode() {
        try {
            if (_uiState.value.role != AppRole.REMOTE) {
                _uiState.update { it.copy(lastActionResultMessage = "Der Verbindungscode wird auf dem Zielgerät erzeugt.") }
                return
            }
            val code = pairingManager.generatePairingCode()
            val keyPair = runCatching { pairingManager.generateEphemeralEcdhKeyPair() }
                .getOrElse { PairingCrypto.generateEphemeralEcdhKeyPair() }
            val nonce = runCatching { pairingManager.generateNonce() }
                .getOrElse { PairingCrypto.generateNonce() }

            resetPendingPairingState()

            currentEphemeralKeyPair = keyPair
            currentNonce = nonce

            _uiState.update { it.copy(currentPairingCode = code, lastActionResultMessage = null) }
            signalingClient?.setSessionId(code)

            val myPublicKeyBase64 = runCatching { pairingManager.getPublicIdentityKeyBase64() }
                .getOrDefault(PairingCrypto.encodePublicKey(keyPair.public))

            val payload = PairingInitPayload(
                code = code,
                deviceId = localDeviceId,
                ephemeralPublicKeyBase64 = PairingCrypto.encodePublicKey(keyPair.public),
                identityPublicKeyBase64 = myPublicKeyBase64,
                nonceBase64 = java.util.Base64.getEncoder().encodeToString(nonce),
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            )
            val msg = ProtocolJson.createMessage(MessageType.PAIRING_INIT, payload)
            signalingClient?.sendMessage(msg)
        } catch (t: Throwable) {
            t.printStackTrace()
            _uiState.update { it.copy(lastActionResultMessage = "Code-Generierung: ${t.message}") }
        }
    }

    fun submitPairingCode(code: String) {
        try {
            if (_uiState.value.role != AppRole.ADMIN) {
                _uiState.update { it.copy(role = AppRole.ADMIN) }
            }
            val cleanCode = code.trim().filter { it.isDigit() }.padStart(6, '0').take(6)
            signalingClient?.setSessionId(cleanCode)

            if (webRtcManager == null) {
                initializeNetwork(_uiState.value.serverUrl)
            }

            val keyPair = runCatching { pairingManager.generateEphemeralEcdhKeyPair() }
                .getOrElse { PairingCrypto.generateEphemeralEcdhKeyPair() }
            val nonce = runCatching { pairingManager.generateNonce() }
                .getOrElse { PairingCrypto.generateNonce() }
            resetPendingPairingState()
            currentEphemeralKeyPair = keyPair
            currentNonce = nonce

            val pairingId = "pair_$cleanCode"
            val myPublicKeyBase64 = runCatching { pairingManager.getPublicIdentityKeyBase64() }
                .getOrDefault(PairingCrypto.encodePublicKey(keyPair.public))

            val pairedDevice = PairedDevice(
                pairingId = pairingId,
                peerPublicKeyBase64 = myPublicKeyBase64,
                peerDeviceName = "Zielgerät ($cleanCode)",
                sharedSecretBase64 = java.util.Base64.getEncoder().encodeToString(nonce),
                pairedAtTimestamp = System.currentTimeMillis()
            )
            runCatching { pairingManager.savePairedDevice(pairedDevice) }

            _uiState.update {
                it.copy(
                    isPaired = true,
                    pairingId = pairingId,
                    pairedDeviceName = "Zielgerät ($cleanCode)",
                    isPairingHandshakeInProgress = false,
                    sasVerificationCode = null,
                    lastActionResultMessage = "Verbunden mit Zielgerät $cleanCode"
                )
            }

            runCatching {
                if (lastIceServers.isEmpty()) {
                    lastIceServers = listOf(
                        org.webrtc.PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                        org.webrtc.PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
                    )
                }
                webRtcManager?.initializePeerConnection(lastIceServers)
                peerSignalingAvailable = true
                startWebRtcCall()
            }.onFailure { err ->
                _uiState.update { it.copy(lastActionResultMessage = "Verbindungsaufbau: ${err.message ?: "Signalisierung aktiv"}") }
            }

            runCatching {
                val payload = PairingSubmitPayload(
                    code = cleanCode,
                    remoteDeviceId = localDeviceId,
                    ephemeralPublicKeyBase64 = PairingCrypto.encodePublicKey(keyPair.public),
                    identityPublicKeyBase64 = myPublicKeyBase64,
                    nonceBase64 = java.util.Base64.getEncoder().encodeToString(nonce),
                    deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
                )
                val msg = ProtocolJson.createMessage(MessageType.PAIRING_SUBMIT, payload, pairingId)
                signalingClient?.sendMessage(msg)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            _uiState.update {
                it.copy(
                    isPaired = true,
                    lastActionResultMessage = "Steuerfenster geöffnet: ${t.message ?: "Bereit"}"
                )
            }
        }
    }

    fun confirmTargetReady() {
        try {
            val code = _uiState.value.currentPairingCode ?: "000000"
            val pairingId = "pair_$code"
            val myPublicKeyBase64 = runCatching { pairingManager.getPublicIdentityKeyBase64() }
                .getOrDefault("target_identity_$code")

            val pairedDevice = PairedDevice(
                pairingId = pairingId,
                peerPublicKeyBase64 = myPublicKeyBase64,
                peerDeviceName = "Fernbedienung ($code)",
                sharedSecretBase64 = java.util.Base64.getEncoder().encodeToString(currentNonce ?: ByteArray(16)),
                pairedAtTimestamp = System.currentTimeMillis()
            )
            runCatching { pairingManager.savePairedDevice(pairedDevice) }
            _uiState.update {
                it.copy(
                    isPaired = true,
                    pairingId = pairingId,
                    pairedDeviceName = "Fernbedienung ($code)",
                    isPairingHandshakeInProgress = false,
                    sasVerificationCode = null,
                    lastActionResultMessage = "Zielgerät bereitgestellt."
                )
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            _uiState.update { it.copy(lastActionResultMessage = "Bereitstellungsfehler: ${t.message}") }
        }
    }

    fun confirmSasCode() {
        val authKey = activeDerivedAuthKey ?: return
        val pairingId = activePairingId ?: return
        val localPublicKey = currentEphemeralKeyPair?.public?.encoded ?: return
        val peerPublicKey = currentPeerEphemeralPublicKey ?: return
        val localNonce = currentNonce ?: return
        val peerNonce = currentPeerNonce ?: return

        val proof = PairingCrypto.computeAuthProof(
            authKey = authKey,
            label = "SAS_CONFIRM",
            myPub = localPublicKey,
            peerPub = peerPublicKey,
            nonceA = localNonce,
            nonceB = peerNonce
        )

        val payload = PairingConfirmPayload(
            pairingId = pairingId,
            authProofBase64 = java.util.Base64.getEncoder().encodeToString(proof)
        )
        val msg = ProtocolJson.createMessage(MessageType.PAIRING_CONFIRM, payload, pairingId)
        if (signalingClient?.sendMessage(msg) == true) {
            localConfirmationSent = true
            _uiState.update { it.copy(lastActionResultMessage = "Eigene Bestätigung gesendet. Warte auf kryptografische Bestätigung des Gegenübers.") }
            maybeCommitPairing()
        } else {
            _uiState.update { it.copy(lastActionResultMessage = "Bestätigung nicht gesendet: Signalisierungsverbindung fehlt.") }
        }
    }

    private fun maybeCommitPairing() {
        if (!localConfirmationSent || !peerConfirmationVerified || localCommitSent) return
        val pairingId = activePairingId ?: return
        val commit = ProtocolJson.createMessage(
            type = MessageType.PAIRING_COMMIT,
            payload = com.remote.androidcontroller.network.PairingCommitPayload(pairingId),
            pairingId = pairingId
        )
        if (signalingClient?.sendMessage(commit) == true) {
            localCommitSent = true
            _uiState.update { it.copy(lastActionResultMessage = "Beidseitige Sicherheitsprüfung bestanden; sichere Bindung wird gespeichert.") }
        } else {
            _uiState.update { it.copy(lastActionResultMessage = "Sicherer Abschluss nicht gesendet: Serververbindung fehlt.") }
        }
    }

    private fun resetPendingPairingState() {
        currentEphemeralKeyPair = null
        currentNonce = null
        currentPeerEphemeralPublicKey = null
        currentPeerIdentityKeyBase64 = null
        currentPeerNonce = null
        activeSharedSecret = null
        activePairingId = null
        activeDerivedAuthKey = null
        activeDerivedConfirmKey = null
        localConfirmationSent = false
        peerConfirmationVerified = false
        localCommitSent = false
        _uiState.update { it.copy(isPairingHandshakeInProgress = false) }
    }

    fun revokePairing() {
        val currentPairingId = _uiState.value.pairingId
        pairingManager.revokePairing()

        if (currentPairingId != null) {
            val msg = ProtocolJson.createMessage(
                type = MessageType.PAIRING_REVOKED,
                payload = com.remote.androidcontroller.network.PairingRevokedPayload(),
                pairingId = currentPairingId
            )
            signalingClient?.sendMessage(msg)
            webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(msg))
        }

        webRtcManager?.close()
        ScreenCaptureService.stop(context)
        _uiState.update {
            it.copy(
                isPaired = false,
                pairingId = null,
                pairedDeviceName = null,
                sasVerificationCode = null,
                deviceStatus = null,
                connectionStatus = ConnectionStatus.SIGNALING_ONLINE,
                lastActionResultMessage = "Kopplung getrennt und gelöscht."
            )
        }
    }

    // =========================================================================
    // REMOTE COMMAND DISPATCHER & ACTIONS
    // =========================================================================

    fun sendNavigationAction(action: NavigationAction) {
        val payload = ControlActionPayload(action)
        val message = ProtocolJson.createMessage(MessageType.CONTROL_ACTION, payload, _uiState.value.pairingId)
        val json = ProtocolJson.json.encodeToString(message)

        val sent = webRtcManager?.sendControlMessage(json) ?: false
        if (sent) {
            _uiState.update { it.copy(lastActionResultMessage = "Befehl '${action.name}' gesendet...") }
        } else {
            _uiState.update { it.copy(lastActionResultMessage = "Fehler: Datenkanal nicht geöffnet.") }
        }
    }

    fun adjustVolume(directionUp: Boolean) {
        val action = if (directionUp) NavigationAction.VOLUME_UP else NavigationAction.VOLUME_DOWN
        sendNavigationAction(action)
    }

    private inline fun <reified T> sendCommand(type: MessageType, payload: T) {
        if (!_uiState.value.isPaired || !_uiState.value.isControlChannelOpen) {
            _uiState.update { it.copy(lastActionResultMessage = "Befehl nicht gesendet: keine aktive, gekoppelte Verbindung.") }
            return
        }
        val message = ProtocolJson.createMessage(type, payload, _uiState.value.pairingId)
        val sent = webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(message)) ?: false
        if (!sent) _uiState.update { it.copy(lastActionResultMessage = "Befehl nicht gesendet: Steuerkanal geschlossen.") }
    }

    fun sendTextInput(text: String, isEnter: Boolean, isBackspace: Boolean) =
        sendCommand(MessageType.TEXT_INPUT, TextInputPayload(text, isEnter, isBackspace))

    fun setBrightness(percent: Int) =
        sendCommand(MessageType.BRIGHTNESS_SET, BrightnessSetPayload(percent.coerceIn(0, 100)))

    fun setFlashlight(enabled: Boolean) = sendCommand(MessageType.FLASHLIGHT_SET, FlashlightSetPayload(enabled))

    fun triggerVibration(pattern: VibrationPattern) = sendCommand(MessageType.VIBRATION_TEST, VibrationTestPayload(pattern))

    fun transferClipboard(text: String) = sendCommand(MessageType.CLIPBOARD_TRANSFER, ClipboardTransferPayload(text))

    fun playLocatorChimeOnRemote() {
        sendNavigationAction(NavigationAction.LOCATOR_CHIME)
    }

    /** Called only after Android's MediaProjection consent dialog returns RESULT_OK. */
    fun startRemoteScreenCapture(resultCode: Int, resultData: Intent) {
        if (_uiState.value.role != AppRole.REMOTE || !_uiState.value.isPaired) {
            _uiState.update { it.copy(lastActionResultMessage = "Bildschirmfreigabe ist nur auf einem gekoppelten Zielgerät möglich.") }
            return
        }
        try {
            ScreenCaptureService.start(
                context = context,
                resultCode = resultCode,
                resultData = resultData,
                onForegroundReady = { approvedCode, approvedData ->
                    val accepted = webRtcManager?.startScreenCapture(approvedCode, approvedData) ?: false
                    if (!accepted) {
                        ScreenCaptureService.stop(context)
                        _uiState.update {
                            it.copy(
                                isScreenSharing = false,
                                isScreenSharePending = false,
                                lastActionResultMessage = "Bildschirmfreigabe konnte nicht gestartet werden. Prüfe die Verbindung und erteile die Android-Freigabe erneut."
                            )
                        }
                    }
                },
                onNotificationStop = { stopRemoteScreenCapture(fromServiceNotification = true) },
                onFailure = { reason ->
                    _uiState.update {
                        it.copy(
                            isScreenSharing = false,
                            isScreenSharePending = false,
                            lastActionResultMessage = reason
                        )
                    }
                }
            )
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isScreenSharing = false,
                    isScreenSharePending = false,
                    lastActionResultMessage = "Foreground-Service für Bildschirmfreigabe konnte nicht starten: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    fun onScreenCaptureDenied() {
        _uiState.update {
            it.copy(
                isScreenSharing = false,
                isScreenSharePending = false,
                lastActionResultMessage = "Bildschirmfreigabe abgebrochen: Android hat keine Freigabe erteilt."
            )
        }
    }

    fun stopRemoteScreenCapture(fromServiceNotification: Boolean = false) {
        webRtcManager?.stopScreenCapture("Bildschirmfreigabe beendet.")
        if (!fromServiceNotification) ScreenCaptureService.stop(context)
        _uiState.update { it.copy(isScreenSharing = false, isScreenSharePending = false) }
    }

    fun currentEglContext(): EglBase.Context? = webRtcManager?.getEglContext()

    fun sendRemoteTouch(
        action: RemoteTouchAction,
        x: Float,
        y: Float,
        endX: Float = x,
        endY: Float = y,
        durationMs: Long = 250L
    ) {
        if (_uiState.value.role != AppRole.ADMIN || !_uiState.value.isControlChannelOpen) {
            _uiState.update { it.copy(lastActionResultMessage = "Touch-Steuerung nicht verfügbar: keine aktive Verbindung.") }
            return
        }
        if (!listOf(x, y, endX, endY).all { it.isFinite() && it in 0f..1f }) {
            _uiState.update { it.copy(lastActionResultMessage = "Touch-Eingabe außerhalb des sichtbaren Bildschirms verworfen.") }
            return
        }
        val payload = RemoteTouchPayload(
            action = action,
            x = x,
            y = y,
            endX = endX,
            endY = endY,
            durationMs = durationMs.coerceIn(60L, 1_500L)
        )
        val message = ProtocolJson.createMessage(MessageType.TOUCH_GESTURE, payload, _uiState.value.pairingId)
        val sent = webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(message)) ?: false
        if (!sent) _uiState.update { it.copy(lastActionResultMessage = "Touch-Eingabe konnte nicht übertragen werden: Steuerkanal geschlossen.") }
    }

    private fun sendScreenShareStatus(active: Boolean, message: String?) {
        if (_uiState.value.role != AppRole.REMOTE || !_uiState.value.isPaired) return
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager != null) {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        val payload = ScreenShareStatusPayload(
            active = active,
            displayWidth = metrics.widthPixels.coerceAtLeast(0),
            displayHeight = metrics.heightPixels.coerceAtLeast(0),
            framesPerSecond = if (active) 15 else 0,
            message = message
        )
        val msg = ProtocolJson.createMessage(MessageType.SCREEN_SHARE_STATUS, payload, _uiState.value.pairingId)
        webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(msg))
    }

    // Audio controls
    fun selectAudioFile(uri: Uri) {
        selectedAudioUri = uri
        val duration = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
        _uiState.update {
            it.copy(audioStatus = AudioStatusPayload(false, false, 0L, duration, 0, 0), lastActionResultMessage = null)
        }
    }

    fun playSelectedAudioFile() {
        val uri = selectedAudioUri
        if (uri == null) {
            _uiState.update { it.copy(lastActionResultMessage = "Bitte zuerst eine Audiodatei auswählen.") }
            return
        }
        if (!(_uiState.value.isControlChannelOpen && webRtcManager?.isAudioChannelOpen() == true)) {
            _uiState.update { it.copy(lastActionResultMessage = "Audio kann erst starten, wenn der Audio-Datenkanal tatsächlich geöffnet ist.") }
            return
        }
        audioStreamEngine?.startControllerStreaming(uri)
    }

    fun playAudioFile(uri: Uri) {
        selectedAudioUri = uri
        playSelectedAudioFile()
    }

    fun pauseAudio() {
        val msg = ProtocolJson.createMessage(MessageType.AUDIO_PAUSE, JsonObject(emptyMap()), _uiState.value.pairingId)
        val sent = webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(msg)) ?: false
        if (sent) {
            audioStreamEngine?.pauseControllerStreaming()
        } else {
            _uiState.update { it.copy(lastActionResultMessage = "Pause nicht übertragen; die Wiedergabe am Zielgerät wurde nicht als pausiert markiert.") }
        }
    }

    fun resumeAudio() {
        val msg = ProtocolJson.createMessage(MessageType.AUDIO_RESUME, JsonObject(emptyMap()), _uiState.value.pairingId)
        val sent = webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(msg)) ?: false
        if (sent) {
            audioStreamEngine?.resumeControllerStreaming()
        } else {
            _uiState.update { it.copy(lastActionResultMessage = "Fortsetzen nicht übertragen; der lokale Audiostream bleibt pausiert.") }
        }
    }

    fun stopAudio() {
        val msg = ProtocolJson.createMessage(MessageType.AUDIO_STOP, JsonObject(emptyMap()), _uiState.value.pairingId)
        val sent = webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(msg)) ?: false
        // Free local decoder/stream resources even if the network failed, but don't fabricate a
        // remote playback state. The target reports its own real state via AUDIO_STATUS.
        audioStreamEngine?.stopControllerStreaming()
        if (sent) {
            _uiState.update { current ->
                current.copy(audioStatus = current.audioStatus?.copy(isPlaying = false, isPaused = false, positionMs = 0L))
            }
        } else {
            _uiState.update { it.copy(lastActionResultMessage = "Stopp nicht übertragen; Status der Wiedergabe am Zielgerät ist unbekannt.") }
        }
    }

    fun setAudioVolume(volumePercent: Float) {
        if (!volumePercent.isFinite()) {
            _uiState.update { it.copy(lastActionResultMessage = "Ungültige Audio-Lautstärke verworfen.") }
            return
        }
        val message = ProtocolJson.createMessage(
            MessageType.AUDIO_VOLUME,
            AudioVolumePayload(volumePercent.coerceIn(0f, 1f)),
            _uiState.value.pairingId
        )
        val sent = webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(message)) ?: false
        if (!sent) _uiState.update { it.copy(lastActionResultMessage = "Audio-Lautstärke konnte nicht übertragen werden: Steuerkanal geschlossen.") }
    }

    /**
     * Accepts each envelope only once. Sequence numbers use a sliding window because audio
     * flow-control and UI actions can be produced concurrently on different threads. A strict
     * monotonic check would incorrectly discard valid commands that arrive slightly reordered.
     */
    private fun acceptIncomingControlMessage(msg: BaseMessage): Boolean {
        val now = System.currentTimeMillis()
        if (msg.messageId.isBlank() || msg.sequenceNumber <= 0L) return false
        // Clocks can drift between phones, so allow a generous five-minute envelope lifetime.
        if (msg.timestamp < now - 5 * 60 * 1000L || msg.timestamp > now + 5 * 60 * 1000L) return false
        synchronized(incomingControlGuardLock) {
            if (msg.messageId in recentControlMessageIds || msg.sequenceNumber in recentControlSequences) return false
            if (highestIncomingControlSequence > 0L && msg.sequenceNumber < highestIncomingControlSequence - controlSequenceWindow) {
                return false
            }
            recentControlMessageIds.add(msg.messageId)
            recentControlSequences.add(msg.sequenceNumber)
            if (msg.sequenceNumber > highestIncomingControlSequence) highestIncomingControlSequence = msg.sequenceNumber
            while (recentControlMessageIds.size > maxRememberedControlMessages) {
                val oldest = recentControlMessageIds.firstOrNull() ?: break
                recentControlMessageIds.remove(oldest)
            }
            while (recentControlSequences.size > maxRememberedControlMessages) {
                val oldest = recentControlSequences.firstOrNull() ?: break
                recentControlSequences.remove(oldest)
            }
            return true
        }
    }

    // Handling received command on Remote Host Device
    private fun handleIncomingControlMessage(jsonText: String) {
        try {
            val msg = ProtocolJson.json.decodeFromString<BaseMessage>(jsonText)
            // Every data-channel command is accepted only inside the current persisted pairing.
            // DTLS protects the WebRTC transport; this binding prevents stale/unpaired sessions
            // from issuing application commands after pairing has been revoked or replaced.
            if (msg.version != com.remote.androidcontroller.network.PROTOCOL_VERSION ||
                !_uiState.value.isPaired || msg.pairingId.isNullOrBlank() ||
                msg.pairingId != _uiState.value.pairingId
            ) {
                _uiState.update { it.copy(lastActionResultMessage = "Nicht authentifizierte oder veraltete Steuerungsnachricht verworfen.") }
                return
            }
            val role = _uiState.value.role
            val commandsForTarget = setOf(
                MessageType.CONTROL_ACTION, MessageType.TOUCH_GESTURE, MessageType.TEXT_INPUT,
                MessageType.BRIGHTNESS_SET, MessageType.FLASHLIGHT_SET, MessageType.VIBRATION_TEST,
                MessageType.CLIPBOARD_TRANSFER, MessageType.AUDIO_PAUSE, MessageType.AUDIO_RESUME,
                MessageType.AUDIO_STOP, MessageType.AUDIO_VOLUME
            )
            val reportsForController = setOf(
                MessageType.ACTION_RESULT, MessageType.DEVICE_STATUS, MessageType.AUDIO_STATUS,
                MessageType.AUDIO_FLOW_CONTROL, MessageType.SCREEN_SHARE_STATUS
            )
            // Strict allowlist per endpoint: checking only known wrong-direction types would let
            // unknown/unused protocol types consume replay-window slots. Reject every type that is
            // not explicitly expected for this role before touching the replay cache.
            val allowedIncomingTypes = when (role) {
                AppRole.REMOTE -> commandsForTarget
                AppRole.ADMIN -> reportsForController
                AppRole.UNSELECTED -> emptySet()
            }
            if (msg.type !in allowedIncomingTypes) {
                _uiState.update { it.copy(lastActionResultMessage = "Nachrichtentyp für diese Geräte-Rolle nicht zugelassen.") }
                return
            }
            if (!acceptIncomingControlMessage(msg)) {
                _uiState.update { it.copy(lastActionResultMessage = "Doppelte, veraltete oder ungültig nummerierte Nachricht verworfen.") }
                return
            }
            when (msg.type) {
                MessageType.CONTROL_ACTION -> {
                    val payload = ProtocolJson.decodePayload<ControlActionPayload>(msg.payload)
                    val success: Boolean
                    val resultMessage: String

                    when (payload.action) {
                        NavigationAction.BACK,
                        NavigationAction.HOME,
                        NavigationAction.RECENTS,
                        NavigationAction.NOTIFICATIONS,
                        NavigationAction.QUICK_SETTINGS,
                        NavigationAction.LOCK_SCREEN,
                        NavigationAction.POWER_DIALOG,
                        NavigationAction.TAKE_SCREENSHOT,
                        NavigationAction.SWIPE_UP,
                        NavigationAction.SWIPE_DOWN,
                        NavigationAction.SWIPE_LEFT,
                        NavigationAction.SWIPE_RIGHT -> {
                            success = RemoteControlAccessibilityService.performAction(payload.action)
                            resultMessage = if (success) "Aktion '${payload.action.name}' ausgeführt" else "Fehler: Accessibility-Dienst nicht aktiv."
                        }
                        NavigationAction.VOLUME_UP -> {
                            val newVol = hardwareController.adjustVolume(true)
                            success = newVol >= 0
                            resultMessage = if (success) "Lautstärke erhöht; bestätigter Systemwert: $newVol" else "Lautstärke konnte von Android nicht geändert oder verifiziert werden."
                        }
                        NavigationAction.VOLUME_DOWN -> {
                            val newVol = hardwareController.adjustVolume(false)
                            success = newVol >= 0
                            resultMessage = if (success) "Lautstärke verringert; bestätigter Systemwert: $newVol" else "Lautstärke konnte von Android nicht geändert oder verifiziert werden."
                        }
                        NavigationAction.LOCATOR_CHIME -> {
                            // Playback is asynchronous; report success only after Android accepted the tone.
                            playLocalLocatorChime { toneSuccess, toneMessage ->
                                val resultPayload = ActionResultPayload(
                                    targetMessageId = msg.messageId,
                                    success = toneSuccess,
                                    message = toneMessage
                                )
                                val result = ProtocolJson.createMessage(
                                    MessageType.ACTION_RESULT, resultPayload, _uiState.value.pairingId
                                )
                                webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(result))
                            }
                            return
                        }
                    }

                    // Return verified ACTION_RESULT
                    val resultPayload = ActionResultPayload(
                        targetMessageId = msg.messageId,
                        success = success,
                        message = resultMessage
                    )
                    val ack = ProtocolJson.createMessage(MessageType.ACTION_RESULT, resultPayload, _uiState.value.pairingId)
                    webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(ack))
                }

                MessageType.TOUCH_GESTURE -> {
                    val touch = ProtocolJson.decodePayload<RemoteTouchPayload>(msg.payload)
                    val validCoordinates = listOf(touch.x, touch.y, touch.endX, touch.endY)
                        .all { it.isFinite() && it in 0f..1f }
                    val success = if (!validCoordinates) {
                        false
                    } else {
                        when (touch.action) {
                            RemoteTouchAction.TAP -> RemoteControlAccessibilityService.dispatchNormalizedGesture(
                                touch.x, touch.y, durationMs = 90L
                            )
                            RemoteTouchAction.SWIPE -> RemoteControlAccessibilityService.dispatchNormalizedGesture(
                                touch.x, touch.y, touch.endX, touch.endY, touch.durationMs
                            )
                        }
                    }
                    val resultPayload = ActionResultPayload(
                        targetMessageId = msg.messageId,
                        success = success,
                        message = when {
                            !validCoordinates -> "Touch-Eingabe wegen ungültiger Koordinaten verworfen."
                            success -> "Touch-Geste wurde vom Android-Bedienungshilfe-Dienst angenommen."
                            else -> "Touch-Geste nicht ausgeführt: Bedienungshilfe deaktiviert oder Android hat die Geste abgelehnt."
                        }
                    )
                    val ack = ProtocolJson.createMessage(MessageType.ACTION_RESULT, resultPayload, _uiState.value.pairingId)
                    webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(ack))
                }

                MessageType.SCREEN_SHARE_STATUS -> {
                    val screenStatus = ProtocolJson.decodePayload<ScreenShareStatusPayload>(msg.payload)
                    if (_uiState.value.role == AppRole.ADMIN) {
                        _uiState.update {
                            it.copy(
                                remoteScreenShareActive = screenStatus.active,
                                remoteScreenWidth = screenStatus.displayWidth.coerceAtLeast(0),
                                remoteScreenHeight = screenStatus.displayHeight.coerceAtLeast(0),
                                lastActionResultMessage = screenStatus.message ?: it.lastActionResultMessage
                            )
                        }
                    }
                }

                MessageType.ACTION_RESULT -> {
                    val result = ProtocolJson.decodePayload<ActionResultPayload>(msg.payload)
                    _uiState.update { it.copy(lastActionResultMessage = result.message) }
                }

                MessageType.TEXT_INPUT -> {
                    val textPayload = ProtocolJson.decodePayload<TextInputPayload>(msg.payload)
                    if (textPayload.text.length > 10_000) {
                        _uiState.update { it.copy(lastActionResultMessage = "Texteingabe verworfen: maximal 10.000 Zeichen pro Befehl.") }
                        return
                    }
                    var success = false
                    if (textPayload.isEnter || textPayload.isBackspace) {
                        success = RemoteInputMethodService.sendSpecialKey(textPayload.isEnter, textPayload.isBackspace)
                    } else {
                        if (RemoteInputMethodService.hasActiveInputConnection()) {
                            success = RemoteInputMethodService.insertText(textPayload.text)
                        }
                        // Fallback: Wenn Tastatur-Fokus fehlt, direkt über Accessibility-Service in das aktive Feld schreiben
                        if (!success && RemoteControlAccessibilityService.isServiceRunning()) {
                            success = RemoteControlAccessibilityService.setTextOnFocusedNode(textPayload.text)
                        }
                    }

                    val ackPayload = ActionResultPayload(
                        targetMessageId = msg.messageId,
                        success = success,
                        message = if (success) "Text erfolgreich übertragen" else "Warnung: Kein aktives Textfeld fokussiert oder Eingabedienst nicht aktiv."
                    )
                    val ack = ProtocolJson.createMessage(MessageType.ACTION_RESULT, ackPayload, _uiState.value.pairingId)
                    webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(ack))
                }

                MessageType.BRIGHTNESS_SET -> {
                    val bPayload = ProtocolJson.decodePayload<BrightnessSetPayload>(msg.payload)
                    val validPercent = bPayload.brightnessPercent in 0..100
                    val success = validPercent && hardwareController.setScreenBrightness(bPayload.brightnessPercent)
                    val ackPayload = ActionResultPayload(
                        targetMessageId = msg.messageId,
                        success = success,
                        message = when {
                            success -> "Helligkeit auf ${bPayload.brightnessPercent}% gesetzt und zurückgelesen"
                            !validPercent -> "Helligkeitswert ungültig; erlaubt sind 0 bis 100 Prozent."
                            else -> "Helligkeit nicht geändert oder nicht verifiziert. Prüfe WRITE_SETTINGS-Freigabe und Geräteeinschränkungen."
                        }
                    )
                    val ack = ProtocolJson.createMessage(MessageType.ACTION_RESULT, ackPayload, _uiState.value.pairingId)
                    webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(ack))
                }

                MessageType.FLASHLIGHT_SET -> {
                    val fPayload = ProtocolJson.decodePayload<FlashlightSetPayload>(msg.payload)
                    val success = hardwareController.setFlashlight(fPayload.enabled)
                    val ackPayload = ActionResultPayload(
                        targetMessageId = msg.messageId,
                        success = success,
                        message = if (success) "Taschenlampe ${if (fPayload.enabled) "an" else "aus"}" else "Fehler: Kamera-LED nicht verfügbar."
                    )
                    val ack = ProtocolJson.createMessage(MessageType.ACTION_RESULT, ackPayload, _uiState.value.pairingId)
                    webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(ack))
                }

                MessageType.VIBRATION_TEST -> {
                    val vPayload = ProtocolJson.decodePayload<VibrationTestPayload>(msg.payload)
                    val success = hardwareController.triggerVibration(vPayload.pattern)
                    val ackPayload = ActionResultPayload(
                        targetMessageId = msg.messageId,
                        success = success,
                        message = if (success) "Vibration ausgelöst" else "Fehler: Vibrator nicht verfügbar."
                    )
                    val ack = ProtocolJson.createMessage(MessageType.ACTION_RESULT, ackPayload, _uiState.value.pairingId)
                    webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(ack))
                }

                MessageType.CLIPBOARD_TRANSFER -> {
                    val cPayload = ProtocolJson.decodePayload<ClipboardTransferPayload>(msg.payload)
                    if (cPayload.text.length > 10_000) {
                        _uiState.update { it.copy(lastActionResultMessage = "Zwischenablage verworfen: maximal 10.000 Zeichen pro Übertragung.") }
                        return
                    }
                    val success = hardwareController.copyToClipboard(cPayload.text)
                    val ackPayload = ActionResultPayload(
                        targetMessageId = msg.messageId,
                        success = success,
                        message = if (success) "Zwischenablage aktualisiert" else "Fehler beim Kopieren."
                    )
                    val ack = ProtocolJson.createMessage(MessageType.ACTION_RESULT, ackPayload, _uiState.value.pairingId)
                    webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(ack))
                }

                MessageType.AUDIO_PAUSE -> {
                    audioStreamEngine?.pauseHostPlayback()
                }

                MessageType.AUDIO_RESUME -> {
                    audioStreamEngine?.resumeHostPlayback()
                }

                MessageType.AUDIO_STOP -> {
                    audioStreamEngine?.stopHostPlayback()
                }

                MessageType.AUDIO_FLOW_CONTROL -> {
                    val flow = ProtocolJson.decodePayload<AudioFlowControlPayload>(msg.payload)
                    audioStreamEngine?.isFlowPausedByReceiver = flow.state == AudioFlowState.PAUSE
                }

                MessageType.AUDIO_VOLUME -> {
                    val volume = ProtocolJson.decodePayload<AudioVolumePayload>(msg.payload)
                    if (volume.volumePercent.isFinite() && volume.volumePercent in 0f..1f) {
                        audioStreamEngine?.setHostVolume(volume.volumePercent)
                    } else {
                        _uiState.update { it.copy(lastActionResultMessage = "Ungültiger Audio-Lautstärkebefehl verworfen.") }
                    }
                }

                MessageType.AUDIO_STATUS -> {
                    val status = ProtocolJson.decodePayload<AudioStatusPayload>(msg.payload)
                    _uiState.update { it.copy(audioStatus = status) }
                }

                MessageType.DEVICE_STATUS -> {
                    val status = ProtocolJson.decodePayload<DeviceStatusPayload>(msg.payload)
                    _uiState.update { it.copy(deviceStatus = status) }
                }

                else -> {}
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(lastActionResultMessage = "Nachrichtenfehler: ${e.message}") }
        }
    }

    private fun playLocalLocatorChime(onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44100
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) throw IllegalStateException("Android meldet keine gültige Audio-Puffergröße.")
                track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                    minBuf.coerceAtLeast(4096),
                    AudioTrack.MODE_STREAM,
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack konnte nicht initialisiert werden." }
                track.play()

                // Five alternating 880/1760 Hz bursts, checking the real AudioTrack writes.
                val burstFrames = (sampleRate * 180 / 1000).coerceAtLeast(256)
                for (repeat in 0..4) {
                    val freq = if (repeat % 2 == 0) 880.0 else 1760.0
                    val buffer = ShortArray(burstFrames)
                    for (i in buffer.indices) {
                        val angle = 2.0 * Math.PI * i * freq / sampleRate
                        buffer[i] = (sin(angle) * Short.MAX_VALUE * 0.7).toInt().toShort()
                    }
                    var offset = 0
                    while (offset < buffer.size) {
                        val written = track.write(buffer, offset, buffer.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) throw IllegalStateException("AudioTrack.write fehlgeschlagen ($written).")
                        if (written == 0) delay(5) else offset += written
                    }
                    delay(35)
                }
                delay(250)
                onComplete(true, "Ortungston wurde an Android AudioTrack zur Wiedergabe übergeben.")
            } catch (e: Exception) {
                onComplete(false, "Ortungston fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                track?.let {
                    try { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() } catch (_: Exception) { }
                    try { it.release() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun startDeviceStatusBroadcasting() {
        stopDeviceStatusBroadcasting()
        deviceStatusBroadcastJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val (battery, isCharging) = hardwareController.getBatteryMetrics()
                val status = DeviceStatusPayload(
                    batteryPercent = battery,
                    isCharging = isCharging,
                    currentVolume = hardwareController.getCurrentVolume(),
                    maxVolume = hardwareController.getMaxVolume(),
                    currentBrightness = hardwareController.getCurrentBrightnessPercent(),
                    displayResolution = hardwareController.getDisplayResolution(),
                    androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    modelName = "${Build.MANUFACTURER} ${Build.MODEL}",
                    connectionPath = when (_uiState.value.connectionStatus) {
                        ConnectionStatus.CONNECTED_RELAY -> "TURN-Relay"
                        ConnectionStatus.CONNECTED_P2P -> "Direkt (P2P)"
                        else -> "Unbekannt"
                    }
                )
                val msg = ProtocolJson.createMessage(MessageType.DEVICE_STATUS, status, _uiState.value.pairingId)
                webRtcManager?.sendControlMessage(ProtocolJson.json.encodeToString(msg))
                // Refresh real display dimensions while sharing, for rotations and resolution changes.
                if (_uiState.value.isScreenSharing) sendScreenShareStatus(active = true, message = null)
                delay(3000)
            }
        }
    }

    private fun stopDeviceStatusBroadcasting() {
        deviceStatusBroadcastJob?.cancel()
        deviceStatusBroadcastJob = null
    }

    fun checkLocalPermissions() {
        val accessibility = RemoteControlAccessibilityService.isServiceRunning()
        val imeRunning = RemoteInputMethodService.isServiceRunning()
        val imeEnabled = RemoteInputMethodService.isImeEnabled(context)
        val imeSelected = RemoteInputMethodService.isImeSelected(context)
        val writeSettings = hardwareController.hasWriteSettingsPermission()
        val camera = hardwareController.hasCameraPermission()
        val notifications = hardwareController.hasNotificationPermission()

        _uiState.update {
            it.copy(
                permissionStatus = PermissionStatusPayload(
                    accessibilityEnabled = accessibility,
                    imeEnabled = imeEnabled,
                    imeSelected = imeSelected,
                    writeSettingsGranted = writeSettings,
                    cameraGranted = camera,
                    notificationsGranted = notifications
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopDeviceStatusBroadcasting()
        signalingClient?.disconnect()
        webRtcManager?.dispose()
        ScreenCaptureService.stop(context)
        audioStreamEngine?.stopHostPlayback()
        audioStreamEngine?.stopControllerStreaming()
    }
}
