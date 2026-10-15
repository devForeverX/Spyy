package com.remote.androidcontroller.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Worldwide cloud signaling client backed by MongoDB Atlas REST / Data API or HTTPS Endpoint.
 * Enables serverless, NAT-traversal-friendly signaling over standard HTTPS from any mobile network.
 */
class MongoSignalingClient(
    val endpointUrl: String,
    val apiKey: String,
    val clusterName: String = "Cluster0",
    val databaseName: String = "remote_control",
    val collectionName: String = "signaling_messages",
    val localDeviceId: String,
    val myRole: String, // "ADMIN" or "REMOTE"
    private val listener: SignalingListener
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var isConnected = AtomicBoolean(false)
    private var lastPollTimestamp = 0L

    var currentSessionId: String = ""
        private set

    fun setSessionId(sessionId: String) {
        currentSessionId = sessionId
        lastPollTimestamp = 0L
    }

    fun generateNewSessionId(): String {
        val newId = UUID.randomUUID().toString().take(8)
        currentSessionId = newId
        lastPollTimestamp = 0L
        return newId
    }

    fun connect() {
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        isConnected.set(true)
        listener.onConnected()
        startPolling()
    }

    fun sendMessage(message: BaseMessage): Boolean {
        if (!isConnected.get()) return false
        val messageJson = ProtocolJson.json.encodeToString(BaseMessage.serializer(), message)

        scope.launch {
            try {
                val targetRole = if (myRole == "ADMIN") "REMOTE" else "ADMIN"
                val isRealMongoApi = apiKey.isNotBlank() && (endpointUrl.contains("data.mongodb-api.com") || endpointUrl.contains("/action/"))

                if (isRealMongoApi) {
                    val documentJson = """
                        {
                            "sessionId": "$currentSessionId",
                            "senderDeviceId": "$localDeviceId",
                            "senderRole": "$myRole",
                            "targetRole": "$targetRole",
                            "messageType": "${message.type.name}",
                            "messageJson": ${Json.encodeToString(kotlinx.serialization.serializer(), messageJson)},
                            "timestamp": ${System.currentTimeMillis()}
                        }
                    """.trimIndent()
                    val postUrl = if (!endpointUrl.endsWith("/insertOne")) endpointUrl.trimEnd('/') + "/insertOne" else endpointUrl
                    val bodyString = """
                        {
                            "dataSource": "$clusterName",
                            "database": "$databaseName",
                            "collection": "$collectionName",
                            "document": $documentJson
                        }
                    """.trimIndent()
                    val reqBuilder = Request.Builder()
                        .url(postUrl)
                        .post(bodyString.toRequestBody(jsonMediaType))
                        .header("apiKey", apiKey)
                        .header("api-key", apiKey)
                    client.newCall(reqBuilder.build()).execute().close()
                } else {
                    // Zero-database cloud rendezvous via open HTTP
                    val topic = "remotectl_${currentSessionId.ifBlank { "global" }}_$targetRole"
                    val relayUrl = "https://ntfy.sh/$topic"
                    val request = Request.Builder()
                        .url(relayUrl)
                        .post(messageJson.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                        .build()
                    client.newCall(request).execute().close()
                }
            } catch (e: Exception) {
                listener.onError("Signalisierung Senden: ${e.message}")
            }
        }
        return true
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && isConnected.get()) {
                if (currentSessionId.isNotBlank()) {
                    fetchPendingMessages()
                }
                delay(600) // Fast 600ms polling for sub-second signaling responsiveness
            }
        }
    }

    private fun fetchPendingMessages() {
        try {
            val isRealMongoApi = apiKey.isNotBlank() && (endpointUrl.contains("data.mongodb-api.com") || endpointUrl.contains("/action/"))
            val targetRole = myRole

            if (isRealMongoApi) {
                val findUrl = if (!endpointUrl.endsWith("/find")) endpointUrl.trimEnd('/') + "/find" else endpointUrl
                val queryBody = """
                    {
                        "dataSource": "$clusterName",
                        "database": "$databaseName",
                        "collection": "$collectionName",
                        "filter": {
                            "sessionId": "$currentSessionId",
                            "targetRole": "$targetRole",
                            "timestamp": { "${'$'}gt": $lastPollTimestamp }
                        },
                        "sort": { "timestamp": 1 }
                    }
                """.trimIndent()
                val reqBuilder = Request.Builder()
                    .url(findUrl)
                    .post(queryBody.toRequestBody(jsonMediaType))
                    .header("apiKey", apiKey)
                    .header("api-key", apiKey)
                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val respText = response.body?.string().orEmpty()
                        if (respText.isNotBlank()) parseAndDispatchMessages(respText)
                    }
                }
            } else {
                // Zero-database cloud relay
                val topic = "remotectl_${currentSessionId.ifBlank { "global" }}_$targetRole"
                val relayUrl = "https://ntfy.sh/$topic/json?poll=1&since=${if (lastPollTimestamp > 0) "${lastPollTimestamp}s" else "10s"}"
                val request = Request.Builder().url(relayUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val respText = response.body?.string().orEmpty()
                        if (respText.isNotBlank()) parseAndDispatchMessages(respText)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun parseAndDispatchMessages(responseJson: String) {
        try {
            // First check if it's newline-delimited JSON (ntfy.sh relay)
            for (line in responseJson.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                try {
                    val parsed = Json.parseToJsonElement(trimmed)
                    if (parsed is JsonObject && parsed.containsKey("message")) {
                        val event = parsed["event"]?.jsonPrimitive?.contentOrNull
                        if (event == "message" || event == null) {
                            val time = parsed["time"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() / 1000)
                            if (time > lastPollTimestamp) {
                                lastPollTimestamp = time
                            }
                            val rawContent = parsed["message"]?.jsonPrimitive?.contentOrNull
                            if (!rawContent.isNullOrBlank()) {
                                try {
                                    val baseMsg = ProtocolJson.json.decodeFromString<BaseMessage>(rawContent)
                                    listener.onMessageReceived(baseMsg)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Also check standard MongoDB Atlas document format
            val element = runCatching { Json.parseToJsonElement(responseJson) }.getOrNull()
            if (element is JsonObject && (element.containsKey("documents") || element.containsKey("document"))) {
                val docs: List<JsonObject> = when {
                    element.containsKey("documents") -> {
                        (element["documents"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                    }
                    element.containsKey("document") -> {
                        listOfNotNull(element["document"] as? JsonObject)
                    }
                    else -> emptyList()
                }
                for (doc in docs) {
                    val ts = doc["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                    if (ts > lastPollTimestamp) {
                        lastPollTimestamp = ts
                    }
                    val msgJson = doc["messageJson"]?.jsonPrimitive?.contentOrNull
                    if (!msgJson.isNullOrBlank()) {
                        try {
                            val baseMsg = ProtocolJson.json.decodeFromString<BaseMessage>(msgJson)
                            listener.onMessageReceived(baseMsg)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        isConnected.set(false)
        pollJob?.cancel()
        pollJob = null
        listener.onDisconnected()
        scope.cancel()
    }
}
