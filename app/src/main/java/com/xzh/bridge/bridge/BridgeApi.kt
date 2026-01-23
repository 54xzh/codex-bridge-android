package com.xzh54.relayouter.bridge

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.IOException
import java.util.UUID

class BridgeApi(
    baseUrl: String,
    private val deviceTokenProvider: () -> String?
) {
    private val gson = Gson()
    private val httpClient = OkHttpClient()
    private val normalizedBaseUrl = normalizeBaseUrl(baseUrl)

    fun getBaseUrl(): String = normalizedBaseUrl

    fun buildWsUrl(): String {
        val http = normalizedBaseUrl.removeSuffix("/")
        return if (http.startsWith("https://")) {
            "wss://${http.removePrefix("https://")}/ws"
        } else {
            "ws://${http.removePrefix("http://")}/ws"
        }
    }

    suspend fun createPairingClaim(pairingCode: String, deviceName: String): PairingClaimResponse {
        val body = JsonObject().apply {
            addProperty("pairingCode", pairingCode.trim())
            addProperty("deviceName", deviceName.trim())
            addProperty("platform", "android")
            addProperty("deviceModel", android.os.Build.MODEL ?: "unknown")
            addProperty("appVersion", "1.0")
        }

        val request = Request.Builder()
            .url("${normalizedBaseUrl}api/v1/connections/pairings/claim")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val json = execute(request)
        return gson.fromJson(json, PairingClaimResponse::class.java)
    }

    suspend fun pollPairing(requestId: String): PairingPollResponse {
        val request = Request.Builder()
            .url("${normalizedBaseUrl}api/v1/connections/pairings/${requestId.trim()}")
            .get()
            .build()

        val json = execute(request)
        return gson.fromJson(json, PairingPollResponse::class.java)
    }

    suspend fun listSessions(): List<SessionSummary> {
        val request = Request.Builder()
            .url("${normalizedBaseUrl}api/v1/sessions?limit=50")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()

        val json = execute(request)
        val array = gson.fromJson(json, Array<SessionSummary>::class.java)
        return array?.toList() ?: emptyList()
    }

    suspend fun getMessages(sessionId: String): List<SessionMessage> {
        val request = Request.Builder()
            .url("${normalizedBaseUrl}api/v1/sessions/${sessionId.trim()}/messages?limit=200")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()

        val json = execute(request)
        val array = gson.fromJson(json, Array<SessionMessage>::class.java)
        return array?.toList() ?: emptyList()
    }

    suspend fun getPlan(sessionId: String): TurnPlanSnapshot? {
        val request = Request.Builder()
            .url("${normalizedBaseUrl}api/v1/sessions/${sessionId.trim()}/plan")
            .header("Authorization", "Bearer ${requireToken()}")
            .get()
            .build()

        return try {
            val json = execute(request)
            gson.fromJson(json, TurnPlanSnapshot::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun connectWebSocket(listener: (BridgeEnvelope) -> Unit, onClosed: (String) -> Unit): WebSocket {
        val wsUrl = buildWsUrl()
        val token = requireToken()

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()

        return httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val env = gson.fromJson(text, BridgeEnvelope::class.java)
                    if (env != null) {
                        listener(env)
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed(t.message ?: "websocket error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(reason.ifEmpty { "closed" })
            }
        })
    }

    fun sendChat(webSocket: WebSocket, prompt: String, sessionId: String?, workingDirectory: String? = null) {
        val data = JsonObject().apply {
            addProperty("prompt", prompt)
            if (!sessionId.isNullOrBlank()) {
                addProperty("sessionId", sessionId)
            }
            if (!workingDirectory.isNullOrBlank()) {
                addProperty("workingDirectory", workingDirectory)
            }
        }

        val env = BridgeEnvelope(
            protocolVersion = 1,
            type = "command",
            name = "chat.send",
            id = UUID.randomUUID().toString().replace("-", ""),
            data = data
        )

        webSocket.send(gson.toJson(env))
    }

    private fun requireToken(): String {
        val token = deviceTokenProvider()?.trim()
        if (token.isNullOrEmpty()) {
            throw IllegalStateException("未配对：缺少 deviceToken")
        }
        return token
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string()
                throw IOException("HTTP ${response.code}: ${body ?: "error"}")
            }

            return@use response.body?.string() ?: ""
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        var text = baseUrl.trim()
        if (text.isEmpty()) {
            return text
        }

        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            text = "http://$text"
        }

        if (!text.endsWith("/")) {
            text += "/"
        }

        return text
    }
}

data class BridgeEnvelope(
    val protocolVersion: Int = 1,
    val type: String,
    val name: String,
    val id: String? = null,
    val ts: String? = null,
    val data: JsonObject? = null
)

data class PairingClaimResponse(
    val requestId: String = "",
    val pollAfterMs: Int = 800,
    val expiresAt: String? = null
)

data class PairingPollResponse(
    val status: String = "",
    val deviceId: String? = null,
    val deviceToken: String? = null,
    val tokenDelivered: Boolean = false,
    val message: String? = null
)

data class SessionSummary(
    val id: String = "",
    val title: String = "",
    val createdAt: String? = null,
    val cwd: String? = null
)

data class SessionMessage(
    val role: String = "",
    val text: String = ""
)

data class TurnPlanSnapshot(
    val sessionId: String? = null,
    val turnId: String? = null,
    val explanation: String? = null,
    val updatedAt: String? = null,
    val plan: List<TurnPlanStep> = emptyList()
)

data class TurnPlanStep(
    val step: String = "",
    val status: String = ""
)

