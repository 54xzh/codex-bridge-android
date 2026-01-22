// 本文件提供"聊天界面"：支持加载历史消息、连接 WS 接收增量输出，并发送 chat.send 指令。
// 使用 M3 Expressive 组件和消息气泡设计
package com.xzh.bridge.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xzh.bridge.bridge.BridgeApi
import com.xzh.bridge.bridge.BridgeEnvelope
import com.xzh.bridge.bridge.SessionMessage
import com.xzh.bridge.bridge.TurnPlanStep
import com.xzh.bridge.ui.theme.AssistantBubbleDark
import com.xzh.bridge.ui.theme.AssistantBubbleLight
import com.xzh.bridge.ui.theme.UserBubbleDark
import com.xzh.bridge.ui.theme.UserBubbleLight
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
    val listState = rememberLazyListState()

    var wsStatus by remember { mutableStateOf("未连接") }
    var webSocket by remember { mutableStateOf<WebSocket?>(null) }

    var sessionId by remember { mutableStateOf(initialSessionId) }
    val messages = remember { mutableStateListOf<ChatUiMessage>() }
    val runToMessageId = remember { mutableStateMapOf<String, String>() }
    var plan by remember { mutableStateOf<List<TurnPlanStep>>(emptyList()) }

    var prompt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

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
            isLoading = true
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
        } finally {
            isLoading = false
        }
    }

    // 自动滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val isConnected = webSocket != null

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding(),
        topBar = {
            MediumTopAppBar(
                title = {
                    Column {
                        Text("聊天")
                        if (!sessionId.isNullOrBlank()) {
                            Text(
                                text = sessionId!!.take(8) + "...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 连接状态指示器
                    ConnectionChip(wsStatus = wsStatus, isConnected = isConnected)
                },
                colors = TopAppBarDefaults.mediumTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 错误提示
            AnimatedVisibility(
                visible = !error.isNullOrBlank(),
                enter = fadeIn() + slideInVertically()
            ) {
                ErrorBanner(error = error ?: "")
            }

            // 计划卡片
            AnimatedVisibility(
                visible = plan.isNotEmpty(),
                enter = fadeIn() + slideInVertically()
            ) {
                PlanCard(plan = plan)
            }

            // 消息列表
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }

            // 输入区域
            ChatInputBar(
                value = prompt,
                onValueChange = { prompt = it },
                onSend = {
                    val ws = webSocket ?: return@ChatInputBar
                    val text = prompt.trim()
                    if (text.isEmpty()) return@ChatInputBar
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
                enabled = isConnected,
                isLoading = isLoading
            )
        }
    }
}

@Composable
private fun ConnectionChip(
    wsStatus: String,
    isConnected: Boolean
) {
    Surface(
        color = if (isConnected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.CloudQueue else Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isConnected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isConnected) "已连接" else "未连接",
                style = MaterialTheme.typography.labelSmall,
                color = if (isConnected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
        }
    }
}

@Composable
private fun ErrorBanner(error: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun PlanCard(plan: List<TurnPlanStep>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "执行计划",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            plan.take(5).forEach { step ->
                PlanStepItem(step = step)
            }
            if (plan.size > 5) {
                Text(
                    text = "还有 ${plan.size - 5} 个步骤...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PlanStepItem(step: TurnPlanStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (icon, tint) = when (step.status.lowercase()) {
            "completed", "done" -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
            "running", "in_progress" -> Icons.Default.PlayArrow to MaterialTheme.colorScheme.tertiary
            else -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.outline
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = step.step,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MessageBubble(message: ChatUiMessage) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // 助手头像
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            // 用户头像
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}


@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    isLoading: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                maxLines = 4
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank() && !isLoading,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送"
                    )
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
