package com.remote.androidcontroller.network

import com.remote.androidcontroller.audio.AudioFlowState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.atomic.AtomicLong

const val PROTOCOL_VERSION = 1

object ProtocolJson {
    @PublishedApi
    internal val sequenceCounter = AtomicLong(0L)

    @PublishedApi
    internal fun nextSequenceNumber(): Long = sequenceCounter.incrementAndGet()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    inline fun <reified T> encodePayload(value: T): JsonElement {
        return json.encodeToJsonElement(value)
    }

    inline fun <reified T> decodePayload(element: JsonElement): T {
        return json.decodeFromJsonElement(element)
    }

    inline fun <reified T> createMessage(
        type: MessageType,
        payload: T,
        pairingId: String? = null,
        sequenceNumber: Long = nextSequenceNumber(),
        messageId: String = "msg_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}"
    ): BaseMessage {
        return BaseMessage(
            version = PROTOCOL_VERSION,
            type = type,
            messageId = messageId,
            sequenceNumber = sequenceNumber,
            timestamp = System.currentTimeMillis(),
            pairingId = pairingId,
            payload = encodePayload(payload)
        )
    }
}

@Serializable
enum class MessageType {
    // Signaling Registration & ICE
    REGISTER,
    REGISTER_ACK,
    REGISTER_AUTH,
    ICE_SERVERS,
    PEER_ONLINE,

    // Pairing & Authentication Handshake
    PAIRING_INIT,
    PAIRING_INIT_ACK,
    PAIRING_SUBMIT,
    PAIRING_RESPONSE,
    PAIRING_HANDSHAKE_INIT,
    PAIRING_CONFIRM,
    PAIRING_COMMIT,
    PAIRING_SUCCESS,
    PAIRING_REVOKED,

    // WebRTC Signaling
    SDP_OFFER,
    SDP_ANSWER,
    ICE_CANDIDATE,

    // Keepalive & Status Telemetry
    HEARTBEAT,
    PING,
    PONG,
    DEVICE_STATUS,
    PERMISSION_STATUS,

    // Remote Actions
    CONTROL_ACTION,
    TOUCH_GESTURE,
    SCREEN_SHARE_STATUS,
    ACTION_RESULT,
    TEXT_INPUT,
    VOLUME_SET,
    BRIGHTNESS_SET,
    FLASHLIGHT_SET,
    VIBRATION_TEST,
    CLIPBOARD_TRANSFER,

    // Audio Control & Flow
    AUDIO_START,
    AUDIO_PAUSE,
    AUDIO_RESUME,
    AUDIO_STOP,
    AUDIO_STATUS,
    AUDIO_FLOW_CONTROL,
    AUDIO_VOLUME,

    // Session Termination
    ERROR,
    DISCONNECT
}

@Serializable
enum class RemoteTouchAction {
    TAP,
    SWIPE
}

@Serializable
data class RemoteTouchPayload(
    val action: RemoteTouchAction,
    /** Normalized coordinates in the visible remote screen, inclusive range 0..1. */
    val x: Float,
    val y: Float,
    val endX: Float = x,
    val endY: Float = y,
    val durationMs: Long = 250L
)

@Serializable
data class ScreenShareStatusPayload(
    val active: Boolean,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val framesPerSecond: Int = 15,
    val message: String? = null
)

@Serializable
enum class NavigationAction {
    BACK,
    HOME,
    RECENTS,
    VOLUME_UP,
    VOLUME_DOWN,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    LOCATOR_CHIME,
    LOCK_SCREEN,
    POWER_DIALOG,
    TAKE_SCREENSHOT,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT
}

@Serializable
enum class VibrationPattern {
    SHORT_PULSE,
    DOUBLE_PULSE,
    SOS_ALARM
}

@Serializable
enum class AudioFlowState {
    PAUSE,
    RESUME
}

@Serializable
data class BaseMessage(
    val version: Int = PROTOCOL_VERSION,
    val type: MessageType,
    val messageId: String,
    val sequenceNumber: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val pairingId: String? = null,
    val payload: JsonElement = JsonObject(emptyMap())
)

@Serializable
data class RegisterPayload(
    val deviceId: String,
    val role: String, // "admin" or "remote"
    val identityPublicKeyBase64: String? = null,
    val savedPairingId: String? = null
)

@Serializable
data class RegisterAckPayload(
    val status: String,
    val deviceId: String = "",
    val role: String = "",
    val challengeBase64: String? = null
)

@Serializable
data class RegisterAuthPayload(
    val pairingId: String,
    val deviceId: String,
    val role: String,
    val identityPublicKeyBase64: String,
    val challengeBase64: String,
    val signatureBase64: String
)

@Serializable
data class PairingInitPayload(
    val code: String,
    val deviceId: String,
    val ephemeralPublicKeyBase64: String,
    val identityPublicKeyBase64: String,
    val nonceBase64: String,
    val deviceName: String
)

@Serializable
data class PairingSubmitPayload(
    val code: String,
    val remoteDeviceId: String,
    val ephemeralPublicKeyBase64: String,
    val identityPublicKeyBase64: String,
    val nonceBase64: String,
    val deviceName: String
)

@Serializable
data class PairingResponsePayload(
    val code: String,
    val remoteDeviceId: String,
    val remoteDeviceName: String,
    val ephemeralPublicKeyBase64: String,
    val identityPublicKeyBase64: String,
    val nonceBase64: String
)

@Serializable
data class PairingHandshakeInitPayload(
    val code: String,
    val adminDeviceId: String,
    val ephemeralPublicKeyBase64: String,
    val identityPublicKeyBase64: String,
    val nonceBase64: String
)

@Serializable
data class PairingConfirmPayload(
    val pairingId: String,
    val authProofBase64: String
)

@Serializable
data class PairingCommitPayload(
    val pairingId: String
)

@Serializable
data class PairingSuccessPayload(
    val pairingId: String,
    val status: String,
    val pairedAt: Long
)

@Serializable
data class PairingRevokedPayload(
    val reason: String = "Vom Nutzer widerrufen"
)

@Serializable
data class SdpPayload(
    val type: String, // "offer" or "answer"
    val sdp: String
)

@Serializable
data class IceCandidatePayload(
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
    val sdp: String
)

@Serializable
data class IceServersPayload(
    val iceServers: List<IceServerEntry> = emptyList(),
    val ttlSeconds: Long = 3600L
)

@Serializable
data class IceServerEntry(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null
)

@Serializable
data class ControlActionPayload(
    val action: NavigationAction
)

@Serializable
data class ActionResultPayload(
    val targetMessageId: String,
    val success: Boolean,
    val message: String,
    val executedAt: Long = System.currentTimeMillis()
)

@Serializable
data class TextInputPayload(
    val text: String = "",
    val isEnter: Boolean = false,
    val isBackspace: Boolean = false
)

@Serializable
data class VolumeSetPayload(
    val volumeLevel: Int,
    val maxVolume: Int
)

@Serializable
data class BrightnessSetPayload(
    val brightnessPercent: Int // 0..100
)

@Serializable
data class FlashlightSetPayload(
    val enabled: Boolean
)

@Serializable
data class VibrationTestPayload(
    val pattern: VibrationPattern
)

@Serializable
data class ClipboardTransferPayload(
    val text: String
)

@Serializable
data class DeviceStatusPayload(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val currentVolume: Int,
    val maxVolume: Int,
    val currentBrightness: Int? = null,
    val displayResolution: String,
    val androidVersion: String,
    val modelName: String,
    val connectionPath: String // "Direct P2P (Host)" or "TURN Relay"
)

@Serializable
data class AudioStartPayload(
    val fileName: String,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
    val totalBytes: Long,
    val durationSeconds: Long
)

@Serializable
data class AudioStatusPayload(
    val isPlaying: Boolean,
    val isPaused: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val bufferFillPercent: Int,
    val underrunCount: Int
)

@Serializable
data class AudioFlowControlPayload(
    val state: AudioFlowState
)

@Serializable
data class AudioVolumePayload(
    val volumePercent: Float
)

@Serializable
data class PermissionStatusPayload(
    val accessibilityEnabled: Boolean,
    val imeEnabled: Boolean,
    val imeSelected: Boolean,
    val writeSettingsGranted: Boolean,
    val cameraGranted: Boolean,
    val notificationsGranted: Boolean
)

@Serializable
data class ErrorPayload(
    val errorCode: String,
    val errorMessage: String
)
