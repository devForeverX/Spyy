package com.remote.androidcontroller

import com.remote.androidcontroller.network.ActionResultPayload
import com.remote.androidcontroller.network.BaseMessage
import com.remote.androidcontroller.network.ControlActionPayload
import com.remote.androidcontroller.network.MessageType
import com.remote.androidcontroller.network.NavigationAction
import com.remote.androidcontroller.network.ProtocolJson
import com.remote.androidcontroller.network.RegisterPayload
import com.remote.androidcontroller.network.RemoteTouchAction
import com.remote.androidcontroller.network.RemoteTouchPayload
import com.remote.androidcontroller.network.ScreenShareStatusPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSerializationTest {

    @Test
    fun testBaseMessageRoundtripWithStructuredPayload() {
        val payloadObj = ControlActionPayload(NavigationAction.BACK)
        val message = ProtocolJson.createMessage(
            type = MessageType.CONTROL_ACTION,
            payload = payloadObj,
            pairingId = "pair_abc123",
            sequenceNumber = 42L,
            messageId = "test_msg_1001"
        )

        val serialized = ProtocolJson.json.encodeToString(message)

        // Verify that the payload is serialized as a real JSON object (not a double-escaped string)
        assertTrue(serialized.contains("\"payload\":{\"action\":\"BACK\"}"))

        val deserialized = ProtocolJson.json.decodeFromString<BaseMessage>(serialized)

        assertEquals(MessageType.CONTROL_ACTION, deserialized.type)
        assertEquals("test_msg_1001", deserialized.messageId)
        assertEquals(42L, deserialized.sequenceNumber)
        assertEquals("pair_abc123", deserialized.pairingId)

        val decodedPayload = ProtocolJson.decodePayload<ControlActionPayload>(deserialized.payload)
        assertEquals(NavigationAction.BACK, decodedPayload.action)
    }

    @Test
    fun testCompatibilityWithTypeScriptSignalingJson() {
        // Exact JSON wire envelope produced by server/src/signaling.ts
        val wireJsonFromTypeScript = """
        {
          "version": 1,
          "type": "REGISTER",
          "messageId": "reg_12345",
          "sequenceNumber": 1,
          "timestamp": 1728900000000,
          "pairingId": "pair_xyz789",
          "payload": {
            "deviceId": "PHONE_GALAXY_S24",
            "role": "admin",
            "identityPublicKeyBase64": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
            "savedPairingId": "pair_xyz789"
          }
        }
        """.trimIndent()

        val parsed = ProtocolJson.json.decodeFromString<BaseMessage>(wireJsonFromTypeScript)
        assertEquals(MessageType.REGISTER, parsed.type)
        assertEquals("reg_12345", parsed.messageId)
        assertEquals("pair_xyz789", parsed.pairingId)

        val regPayload = ProtocolJson.decodePayload<RegisterPayload>(parsed.payload)
        assertEquals("PHONE_GALAXY_S24", regPayload.deviceId)
        assertEquals("admin", regPayload.role)
        assertEquals("pair_xyz789", regPayload.savedPairingId)
    }

    @Test
    fun testNormalizedTouchGestureSerialization() {
        val payload = RemoteTouchPayload(
            action = RemoteTouchAction.SWIPE,
            x = 0.12f,
            y = 0.2f,
            endX = 0.82f,
            endY = 0.71f,
            durationMs = 420L
        )
        val message = ProtocolJson.createMessage(
            type = MessageType.TOUCH_GESTURE,
            payload = payload,
            pairingId = "paired-device-1"
        )
        val serialized = ProtocolJson.json.encodeToString(message)
        val decodedEnvelope = ProtocolJson.json.decodeFromString<BaseMessage>(serialized)
        val decoded = ProtocolJson.decodePayload<RemoteTouchPayload>(decodedEnvelope.payload)

        assertEquals(MessageType.TOUCH_GESTURE, decodedEnvelope.type)
        assertEquals("paired-device-1", decodedEnvelope.pairingId)
        assertEquals(RemoteTouchAction.SWIPE, decoded.action)
        assertEquals(0.12f, decoded.x, 0.0001f)
        assertEquals(0.82f, decoded.endX, 0.0001f)
        assertEquals(420L, decoded.durationMs)
    }

    @Test
    fun testScreenShareStatusSerialization() {
        val message = ProtocolJson.createMessage(
            type = MessageType.SCREEN_SHARE_STATUS,
            payload = ScreenShareStatusPayload(
                active = true,
                displayWidth = 1080,
                displayHeight = 2400,
                framesPerSecond = 15,
                message = "freigegeben"
            ),
            pairingId = "paired-device-1"
        )
        val envelope = ProtocolJson.json.decodeFromString<BaseMessage>(ProtocolJson.json.encodeToString(message))
        val payload = ProtocolJson.decodePayload<ScreenShareStatusPayload>(envelope.payload)

        assertEquals(MessageType.SCREEN_SHARE_STATUS, envelope.type)
        assertTrue(payload.active)
        assertEquals(1080, payload.displayWidth)
        assertEquals(2400, payload.displayHeight)
        assertEquals(15, payload.framesPerSecond)
    }

    @Test
    fun testActionResultSerialization() {
        val result = ActionResultPayload(
            targetMessageId = "msg_77",
            success = true,
            message = "Aktion erfolgreich ausgeführt"
        )
        val json = ProtocolJson.json.encodeToString(result)
        val decoded = ProtocolJson.json.decodeFromString<ActionResultPayload>(json)

        assertTrue(decoded.success)
        assertEquals("msg_77", decoded.targetMessageId)
    }
}
