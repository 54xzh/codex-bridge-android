// 本文件提供“聊天界面”骨架：支持加载历史消息、连接 WS 接收增量输出，并发送 chat.send 指令。
package com.xzh.bridge.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xzh.bridge.bridge.BridgeApi
import com.xzh.bridge.bridge.BridgeEnvelope
import com.xzh.bridge.bridge.SessionMessage
import com.xzh.bridge.bridge.TurnPlanStep
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import java.util.UUID

private data class ChatUiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    api: BridgeApi,
    initialSessionId: String?,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var wsStatus by remember { mutableStateOf("未连接") }
    var webSocket by remember { mutableStateOf<WebSocket?>(null) }

    var sessionId by remember { mutableStateOf(initialSessionId) }
    val messages = remember { mutableStateListOf<ChatUiMessage>() }
    val runToMessageId = remember { mutableStateMapOf<String, String>() }
    var plan by remember { mutableStateOf<List<TurnPlanStep>>(emptyList()) }

    var prompt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun connectWs() {
        webSocket?.close(1000, "reconnect")
        wsStatus = "连接中…"
        webSocket = api.connectWebSocket(
            listener = { env ->
                handleWsEvent(
                    env = env,
                    onSessionCreated = { newId ->
                        if (!newId.isNullOrBlank() && sessionId.isNullOrBlank()) {
                            sessionId = newId
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
                    onPlanUpdated = { updatedSessionId, steps ->
                        if (sessionId.isNullOrBlank() || sessionId == updatedSessionId) {
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

    DisposableEffect(api.getBaseUrl()) {
        connectWs()
        onDispose {
            webSocket?.close(1000, "dispose")
            webSocket = null
        }
    }

    LaunchedEffect(sessionId) {
        val current = sessionId ?: return@LaunchedEffect
        try {
            error = null
            messages.clear()
            plan = emptyList()
            runToMessageId.clear()

            val history = api.getMessages(current)
            history.forEach { m -> messages.add(m.toUiMessage()) }

            val snapshot = api.getPlan(current)
            if (snapshot != null) {
                plan = snapshot.plan
            }
        } catch (ex: Exception) {
            error = ex.message ?: "加载失败"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("聊天") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
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
            if (!sessionId.isNullOrBlank()) {
                Text("会话: $sessionId", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            if (!error.isNullOrBlank()) {
                Text("错误: $error", color = MaterialTheme.colorScheme.error)
            }

            if (plan.isNotEmpty()) {
                Text("计划（plan）", style = MaterialTheme.typography.titleSmall)
                plan.take(6).forEach { step ->
                    Text("- ${step.status}: ${step.step}", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(messages, key = { it.id }) { message ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            message.role,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.alpha(0.7f)
                        )
                        Text(message.text)
                    }
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("输入消息") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val ws = webSocket ?: return@Button
                        val text = prompt.trim()
                        if (text.isEmpty()) return@Button
                        prompt = ""
                        scope.launch {
                            try {
                                error = null
                                api.sendChat(ws, text, sessionId)
                            } catch (ex: Exception) {
                                error = ex.message ?: "发送失败"
                            }
                        }
                    },
                    enabled = webSocket != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("发送")
                }
            }
        }
    }
}

private fun SessionMessage.toUiMessage(): ChatUiMessage {
    return ChatUiMessage(role = role, text = text)
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
            val createdSessionId = data.get("sessionId")?.asString
            onSessionCreated(createdSessionId)
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

