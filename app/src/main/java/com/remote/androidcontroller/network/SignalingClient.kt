package com.remote.androidcontroller.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

interface SignalingListener {
    fun onConnected()
    fun onDisconnected()
    fun onMessageReceived(message: BaseMessage)
    fun onError(reason: String)
}

class SignalingClient(
    private val serverUrl: String,
    private val listener: SignalingListener,
    private val localDeviceId: String = "",
    private val role: String = "admin"
) {
    private val isMongoMode = serverUrl.isBlank() ||
            serverUrl.startsWith("mongodb", ignoreCase = true) ||
            serverUrl.startsWith("https://", ignoreCase = true)

    private val mongoClient: MongoSignalingClient? = if (isMongoMode) {
        val endpoint = if (serverUrl.startsWith("https://", ignoreCase = true)) {
            serverUrl
        } else {
            "https://data.mongodb-api.com/app/remote-ctrl/endpoint/data/v1/action"
        }
        MongoSignalingClient(
            endpointUrl = endpoint,
            apiKey = "",
            localDeviceId = localDeviceId,
            myRole = if (role.equals("admin", true)) "ADMIN" else "REMOTE",
            listener = object : SignalingListener {
                override fun onConnected() = listener.onConnected()
                override fun onDisconnected() = listener.onDisconnected()
                override fun onMessageReceived(message: BaseMessage) = listener.onMessageReceived(message)
                override fun onError(reason: String) = listener.onError(reason)
            }
        )
    } else null

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null

    private var currentBackoffMs = 1000L
    private val maxBackoffMs = 30000L
    private var shouldReconnect = true

    fun connect() {
        if (isMongoMode) {
            mongoClient?.connect()
            return
        }
        shouldReconnect = true
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        initiateConnection()
    }

    private fun initiateConnection() {
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                currentBackoffMs = 1000L
                startHeartbeat()
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val baseMessage = ProtocolJson.json.decodeFromString<BaseMessage>(text)
                    listener.onMessageReceived(baseMessage)
                } catch (e: Exception) {
                    listener.onError("Parsing error: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                listener.onDisconnected()
                triggerReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopHeartbeat()
                listener.onError("Connection failure: ${t.message}")
                listener.onDisconnected()
                triggerReconnect()
            }
        })
    }

    fun setSessionId(sessionId: String) {
        mongoClient?.setSessionId(sessionId)
    }

    fun sendMessage(message: BaseMessage): Boolean {
        if (isMongoMode) {
            if (!message.pairingId.isNullOrBlank()) {
                mongoClient?.setSessionId(message.pairingId)
            }
            if (message.type == MessageType.PAIRING_INIT) {
                runCatching {
                    ProtocolJson.decodePayload<PairingInitPayload>(message.payload).code
                }.getOrNull()?.let { mongoClient?.setSessionId(it) }
            } else if (message.type == MessageType.PAIRING_SUBMIT) {
                runCatching {
                    ProtocolJson.decodePayload<PairingSubmitPayload>(message.payload).code
                }.getOrNull()?.let { mongoClient?.setSessionId(it) }
            }

            // Handle registration and worldwide STUN routing in serverless MongoDB mode
            if (message.type == MessageType.REGISTER) {
                scope.launch {
                    delay(50)
                    val ackPayload = buildJsonObject {
                        put("status", "OK")
                    }
                    listener.onMessageReceived(
                        ProtocolJson.createMessage(MessageType.REGISTER_ACK, ackPayload)
                    )

                    delay(50)
                    // Provide Google Public STUN servers for global NAT traversal
                    val icePayload = buildJsonObject {
                        putJsonArray("iceServers") {
                            add(buildJsonObject {
                                putJsonArray("urls") {
                                    add(JsonPrimitive("stun:stun.l.google.com:19302"))
                                    add(JsonPrimitive("stun:stun1.l.google.com:19302"))
                                    add(JsonPrimitive("stun:stun2.l.google.com:19302"))
                                }
                            })
                        }
                    }
                    listener.onMessageReceived(
                        ProtocolJson.createMessage(MessageType.ICE_SERVERS, icePayload)
                    )
                }
            }

            // Post message via cloud signaling
            return mongoClient?.sendMessage(message) ?: true
        }

        val json = ProtocolJson.json.encodeToString(message)
        return webSocket?.send(json) ?: false
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(15000)
                val ping = ProtocolJson.createMessage(
                    type = MessageType.PING,
                    payload = kotlinx.serialization.json.JsonObject(emptyMap())
                )
                sendMessage(ping)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun triggerReconnect() {
        if (!shouldReconnect) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(currentBackoffMs)
            currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(maxBackoffMs)
            if (shouldReconnect) {
                initiateConnection()
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        stopHeartbeat()
        reconnectJob?.cancel()
        if (isMongoMode) {
            mongoClient?.disconnect()
        } else {
            webSocket?.close(1000, "Normal closure")
            webSocket = null
        }
        scope.cancel()
    }
}
