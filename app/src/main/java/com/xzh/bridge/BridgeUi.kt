package com.xzh.bridge

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xzh.bridge.bridge.BridgeApi
import com.xzh.bridge.bridge.BridgeEnvelope
import com.xzh.bridge.bridge.PairingPollResponse
import com.xzh.bridge.bridge.SessionMessage
import com.xzh.bridge.bridge.SessionSummary
import com.xzh.bridge.bridge.TurnPlanStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import java.util.UUID
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private class BridgePreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    fun load(): ConnectionConfig? {
        val baseUrl = prefs.getString("baseUrl", null) ?: return null
        val encryptedToken = prefs.getString("deviceTokenEnc", null) ?: return null
        val deviceToken = TokenCrypto.decrypt(encryptedToken) ?: return null
        val deviceId = prefs.getString("deviceId", null) ?: ""
        return ConnectionConfig(baseUrl = baseUrl, deviceToken = deviceToken, deviceId = deviceId)
    }

    fun save(config: ConnectionConfig) {
        val encryptedToken = TokenCrypto.encrypt(config.deviceToken)
        prefs.edit()
            .putString("baseUrl", config.baseUrl)
            .putString("deviceTokenEnc", encryptedToken)
            .putString("deviceId", config.deviceId)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

private data class ConnectionConfig(
    val baseUrl: String,
    val deviceToken: String,
    val deviceId: String
)

private data class ChatUiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String
)

private object TokenCrypto {
    private const val KeyAlias = "codex_bridge_device_token"
    private const val IvSizeBytes = 12
    private const val TagSizeBits = 128

    fun encrypt(plaintext: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IvSizeBytes) return null
            val iv = combined.copyOfRange(0, IvSizeBytes)
            val encrypted = combined.copyOfRange(IvSizeBytes, combined.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TagSizeBits, iv))
            val plain = cipher.doFinal(encrypted)
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KeyAlias, null)
        if (existing is SecretKey) {
            return existing
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }
}

@Composable
fun BridgeApp() {
    val context = LocalContext.current
    val prefs = remember { BridgePreferences(context) }
    var config by remember { mutableStateOf(prefs.load()) }

    if (config == null) {
        PairingScreen(
            onPaired = { newConfig ->
                prefs.save(newConfig)
                config = newConfig
            }
        )
    } else {
        MainScreen(
            config = config!!,
            onReset = {
                prefs.clear()
                config = null
            }
        )
    }
}

@Composable
private fun PairingScreen(onPaired: (ConnectionConfig) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(android.os.Build.MODEL ?: "Android") }
    var qrText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("请输入后端地址与配对码") }
    var isBusy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("连接") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = qrText,
                onValueChange = { input ->
                    qrText = input
                    if (input.startsWith("codex-bridge://pair", ignoreCase = true)) {
                        try {
                            val uri = Uri.parse(input.trim())
                            val parsedBaseUrl = uri.getQueryParameter("baseUrl")
                            val parsedCode = uri.getQueryParameter("pairingCode")
                            if (!parsedBaseUrl.isNullOrBlank()) {
                                baseUrl = parsedBaseUrl
                            }
                            if (!parsedCode.isNullOrBlank()) {
                                pairingCode = parsedCode
                            }
                            status = "已解析二维码内容"
                        } catch (_: Exception) {
                            status = "二维码内容解析失败"
                        }
                    }
                },
                label = { Text("二维码内容（可选，粘贴 codex-bridge://pair?...）") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("后端地址（例如 192.168.1.10:12345 或 http://...）") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it },
                label = { Text("配对码（pairingCode）") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("设备名称") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (isBusy) return@Button
                    isBusy = true
                    status = "发起配对…"

                    scope.launch {
                        try {
                            val api = BridgeApi(baseUrl) { null }
                            val claim = api.createPairingClaim(pairingCode, deviceName)
                            status = "等待电脑确认…"

                            while (true) {
                                delay(claim.pollAfterMs.toLong().coerceAtLeast(200))
                                val poll = api.pollPairing(claim.requestId)
                                val done = handlePollResult(poll, baseUrl, onPaired) { msg ->
                                    status = msg
                                }
                                if (done) {
                                    isBusy = false
                                    return@launch
                                }
                            }
                        } catch (ex: Exception) {
                            status = "配对失败: ${ex.message ?: "error"}"
                            isBusy = false
                        }
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isBusy) "处理中…" else "开始配对")
            }

            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "提示：需要在 Windows 端确认后才能完成配对；完成后会保存 deviceToken。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun handlePollResult(
    poll: PairingPollResponse,
    baseUrl: String,
    onPaired: (ConnectionConfig) -> Unit,
    setStatus: (String) -> Unit
): Boolean {
    when (poll.status.lowercase()) {
        "pending" -> {
            return false
        }
        "approved" -> {
            val token = poll.deviceToken
            if (token.isNullOrBlank()) {
                setStatus("已批准，但令牌未返回（可能已领取过）。请重新生成二维码再试。")
                return true
            }
            val config = ConnectionConfig(
                baseUrl = baseUrl.trim(),
                deviceToken = token,
                deviceId = poll.deviceId ?: ""
            )
            onPaired(config)
            return true
        }
        "declined" -> {
            setStatus("电脑端已拒绝该配对请求。")
            return true
        }
        "expired" -> {
            setStatus("配对请求已过期，请重新生成二维码。")
            return true
        }
        "remotedisabled" -> {
            setStatus("远程访问未启用，请在 Windows 端先开启“允许局域网连接”。")
            return true
        }
        else -> {
            val msg = poll.message ?: poll.status
            setStatus("配对失败: $msg")
            return true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(config: ConnectionConfig, onReset: () -> Unit) {
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(config.deviceToken) }
    val api = remember(config.baseUrl) { BridgeApi(config.baseUrl) { token } }

    var wsStatus by remember { mutableStateOf("未连接") }
    var webSocket by remember { mutableStateOf<WebSocket?>(null) }

    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<ChatUiMessage>() }
    val runToMessageId = remember { mutableStateMapOf<String, String>() }
    var plan by remember { mutableStateOf<List<TurnPlanStep>>(emptyList()) }

    var prompt by remember { mutableStateOf("") }

    fun connectWs() {
        webSocket?.close(1000, "reconnect")
        wsStatus = "连接中…"
        webSocket = api.connectWebSocket(
            listener = { env ->
                handleWsEvent(
                    env = env,
                    onSessionCreated = { sessionId ->
                        if (!sessionId.isNullOrBlank()) {
                            currentSessionId = sessionId
                        }
                    },
                    onChatMessage = { role, text, runId ->
                        if (role == "assistant") {
                            val existingId = if (runId.isNullOrBlank()) null else runToMessageId[runId]
                            if (existingId != null) {
                                val idx = messages.indexOfFirst { it.id == existingId }
                                if (idx >= 0) {
                                    messages[idx] = messages[idx].copy(text = text)
                                    return@handleWsEvent
                                }
                            }
                        }
                        messages.add(ChatUiMessage(role = role, text = text))
                    },
                    onChatDelta = { runId, delta ->
                        val key = runId ?: return@handleWsEvent
                        val messageId = runToMessageId[key]
                        if (messageId == null) {
                            val msg = ChatUiMessage(role = "assistant", text = delta)
                            messages.add(msg)
                            runToMessageId[key] = msg.id
                        } else {
                            val idx = messages.indexOfFirst { it.id == messageId }
                            if (idx >= 0) {
                                messages[idx] = messages[idx].copy(text = messages[idx].text + delta)
                            }
                        }
                    },
                    onPlanUpdated = { sessionId, steps ->
                        if (currentSessionId == null || currentSessionId == sessionId) {
                            plan = steps
                        }
                    }
                )
            },
            onClosed = { reason ->
                wsStatus = "已断开: $reason"
                webSocket = null
            }
        )
        wsStatus = "已连接"
    }

    DisposableEffect(config.baseUrl, config.deviceToken) {
        connectWs()
        onDispose {
            webSocket?.close(1000, "dispose")
            webSocket = null
        }
    }

    LaunchedEffect(config.baseUrl, config.deviceToken) {
        try {
            sessions = api.listSessions()
        } catch (_: Exception) {
        }
    }

    fun loadSession(sessionId: String) {
        scope.launch {
            try {
                currentSessionId = sessionId
                messages.clear()
                plan = emptyList()

                val history = api.getMessages(sessionId)
                for (m in history) {
                    messages.add(ChatUiMessage(role = m.role, text = m.text))
                }

                val snapshot = api.getPlan(sessionId)
                if (snapshot != null) {
                    plan = snapshot.plan
                }
            } catch (_: Exception) {
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Codex Bridge") },
                actions = {
                    IconButton(onClick = { scope.launch { sessions = api.listSessions() } }) { Text("刷新") }
                    IconButton(onClick = { onReset() }) { Text("退出") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("后端: ${api.getBaseUrl()}", fontFamily = FontFamily.Monospace)
            Text("WS: $wsStatus", style = MaterialTheme.typography.bodySmall)

            if (currentSessionId == null) {
                Text("会话", style = MaterialTheme.typography.titleMedium)
                Divider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sessions) { s ->
                        Column(modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)) {
                            Text(s.title.ifBlank { s.id }, style = MaterialTheme.typography.bodyLarge)
                            Text(s.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { loadSession(s.id) }) { Text("打开") }
                            }
                            Divider(modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("会话: $currentSessionId", fontFamily = FontFamily.Monospace)
                    Button(onClick = { currentSessionId = null }) { Text("返回") }
                }

                if (plan.isNotEmpty()) {
                    Text("计划（plan）", style = MaterialTheme.typography.titleSmall)
                    for (step in plan.take(6)) {
                        Text("- ${step.status}: ${step.step}", style = MaterialTheme.typography.bodySmall)
                    }
                    Divider()
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(messages) { m ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(m.role, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.7f))
                            Text(m.text)
                        }
                    }
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("输入消息") },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val ws = webSocket ?: return@Button
                        val text = prompt.trim()
                        if (text.isEmpty()) return@Button
                        prompt = ""
                        api.sendChat(ws, text, currentSessionId)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("发送")
                }
            }
        }
    }
}

private fun handleWsEvent(
    env: BridgeEnvelope,
    onSessionCreated: (String?) -> Unit,
    onChatMessage: (role: String, text: String, runId: String?) -> Unit,
    onChatDelta: (runId: String?, delta: String) -> Unit,
    onPlanUpdated: (sessionId: String?, steps: List<TurnPlanStep>) -> Unit
) {
    if (env.type.lowercase() != "event") return
    val data = env.data ?: return

    when (env.name) {
        "session.created" -> {
            val sessionId = data.get("sessionId")?.asString
            onSessionCreated(sessionId)
        }
        "chat.message" -> {
            val role = data.get("role")?.asString ?: return
            val text = data.get("text")?.asString ?: ""
            val runId = data.get("runId")?.asString
            onChatMessage(role, text, runId)
        }
        "chat.message.delta" -> {
            val delta = data.get("delta")?.asString ?: return
            val runId = data.get("runId")?.asString
            onChatDelta(runId, delta)
        }
        "run.plan.updated" -> {
            val threadId = data.get("threadId")?.asString
            val planArray = data.getAsJsonArray("plan") ?: return
            val steps = parsePlanSteps(planArray)
            onPlanUpdated(threadId, steps)
        }
    }
}

private fun parsePlanSteps(plan: com.google.gson.JsonArray): List<TurnPlanStep> {
    val results = mutableListOf<TurnPlanStep>()
    for (elem in plan) {
        if (!elem.isJsonObject) continue
        val obj = elem.asJsonObject
        val step = obj.get("step")?.asString ?: continue
        val status = obj.get("status")?.asString ?: ""
        results.add(TurnPlanStep(step = step, status = status))
    }
    return results
}
