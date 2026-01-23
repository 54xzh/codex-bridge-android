// 本文件提供"聊天界面"：支持加载历史消息、连接 WS 接收增量输出，并发送 chat.send 指令。
// 使用 M3 Expressive 组件（无头像；助手消息为纯文本样式）
package com.xzh54.relayouter.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.xzh54.relayouter.bridge.BridgeApi
import com.xzh54.relayouter.bridge.BridgeEnvelope
import com.xzh54.relayouter.bridge.SessionMessage
import com.xzh54.relayouter.bridge.TurnPlanStep
import kotlinx.coroutines.launch
import okhttp3.WebSocket

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
    val runToSessionId = remember { mutableStateMapOf<String, String?>() }
    var plan by remember { mutableStateOf<List<TurnPlanStep>>(emptyList()) }

    var prompt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var suppressAutoScroll by remember { mutableStateOf(false) }

    fun isEventForCurrentSession(explicitSessionId: String?): Boolean {
        val current = sessionId
        if (current.isNullOrBlank()) return true
        if (explicitSessionId.isNullOrBlank()) return false
        return current == explicitSessionId
    }

    fun isRunForCurrentSession(runId: String): Boolean {
        val current = sessionId
        if (current.isNullOrBlank()) return true
        val mapped = runToSessionId[runId]
        if (mapped.isNullOrBlank()) return false
        return current == mapped
    }

    fun updateMessageById(messageId: String, transform: (ChatUiMessage) -> ChatUiMessage) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            messages[idx] = transform(messages[idx])
        }
    }

    fun getOrCreateRunMessageId(runId: String): String {
        val existingId = runToMessageId[runId]
        if (existingId != null) return existingId

        val msg = ChatUiMessage(
            role = "assistant",
            text = "思考中…",
            runId = runId,
            isTraceExpanded = true
        )
        messages.add(msg)
        runToMessageId[runId] = msg.id
        return msg.id
    }

    fun connectWs() {
        webSocket?.close(1000, "reconnect")
        wsStatus = "连接中…"
        webSocket = api.connectWebSocket(
            listener = { env ->
                handleWsEvent(
                    env = env,
                    onSessionCreated = { runId, newId ->
                        if (!runId.isNullOrBlank() && !newId.isNullOrBlank()) {
                            runToSessionId[runId] = newId
                        }

                        if (!newId.isNullOrBlank() && sessionId.isNullOrBlank()) {
                            sessionId = newId
                        }
                    },
                    onChatMessage = { role, text, runId, explicitSessionId ->
                        val isUser = role.equals("user", ignoreCase = true)
                        if (isUser && !isEventForCurrentSession(explicitSessionId)) return@handleWsEvent

                        if (role.equals("assistant", ignoreCase = true)) {
                            val key = runId?.takeUnless { it.isBlank() } ?: return@handleWsEvent
                            if (!isRunForCurrentSession(key)) return@handleWsEvent

                            val messageId = getOrCreateRunMessageId(key)
                            updateMessageById(messageId) { msg ->
                                val hasDiff = msg.trace.any { it.kind == TraceKind.Diff }
                                msg.copy(text = text, isTraceExpanded = hasDiff)
                            }
                            return@handleWsEvent
                        }

                        messages.add(ChatUiMessage(role = role, text = text))
                    },
                    onChatDelta = { runId, delta ->
                        val key = runId ?: return@handleWsEvent
                        if (!isRunForCurrentSession(key)) return@handleWsEvent
                        val messageId = getOrCreateRunMessageId(key)
                        updateMessageById(messageId) { msg ->
                            val hasDiff = msg.trace.any { it.kind == TraceKind.Diff }
                            val collapsed = if (msg.text == "思考中…" && !hasDiff) {
                                msg.copy(isTraceExpanded = false)
                            } else {
                                msg
                            }
                            val nextText = if (collapsed.text == "思考中…") delta else collapsed.text + delta
                            collapsed.copy(text = nextText)
                        }
                    },
                    onPlanUpdated = { updatedSessionId, steps ->
                        if (sessionId.isNullOrBlank() || sessionId == updatedSessionId) {
                            plan = steps
                        }
                    },
                    onRunStarted = { runId, explicitSessionId ->
                        if (!isEventForCurrentSession(explicitSessionId)) return@handleWsEvent
                        runToSessionId[runId] = explicitSessionId
                        getOrCreateRunMessageId(runId)
                    },
                    onRunCompleted = { runId, explicitSessionId, _ ->
                        if (!explicitSessionId.isNullOrBlank()) {
                            runToSessionId[runId] = explicitSessionId
                        }
                        if (!isRunForCurrentSession(runId)) return@handleWsEvent
                        val messageId = runToMessageId[runId] ?: return@handleWsEvent
                        updateMessageById(messageId) { msg -> msg.copy(isTraceExpanded = false) }
                    },
                    onRunCanceled = { runId, explicitSessionId ->
                        if (!explicitSessionId.isNullOrBlank()) {
                            runToSessionId[runId] = explicitSessionId
                        }
                        if (!isRunForCurrentSession(runId)) return@handleWsEvent
                        val messageId = runToMessageId[runId] ?: return@handleWsEvent
                        updateMessageById(messageId) { msg -> msg.copy(isTraceExpanded = false) }
                    },
                    onRunFailed = { runId, explicitSessionId, message ->
                        if (!explicitSessionId.isNullOrBlank()) {
                            runToSessionId[runId] = explicitSessionId
                        }
                        if (!isRunForCurrentSession(runId)) return@handleWsEvent
                        val messageId = getOrCreateRunMessageId(runId)
                        updateMessageById(messageId) { msg ->
                            val extra = message?.takeUnless { it.isBlank() }?.let { "\n\n$it" }.orEmpty()
                            msg.copy(text = msg.text + extra, isTraceExpanded = false)
                        }
                    },
                    onRunCommand = { runId, itemId, command, status, exitCode, output ->
                        if (!isRunForCurrentSession(runId)) return@handleWsEvent
                        val messageId = getOrCreateRunMessageId(runId)
                        updateMessageById(messageId) { msg ->
                            upsertCommandTrace(msg, itemId, null, command, status, exitCode, output)
                        }
                    },
                    onRunCommandOutputDelta = { runId, itemId, delta ->
                        if (!isRunForCurrentSession(runId)) return@handleWsEvent
                        val messageId = getOrCreateRunMessageId(runId)
                        updateMessageById(messageId) { msg -> appendCommandOutputDelta(msg, itemId, delta) }
                    },
                    onRunReasoning = { runId, itemId, text ->
                        if (!isRunForCurrentSession(runId)) return@handleWsEvent
                        val messageId = getOrCreateRunMessageId(runId)
                        updateMessageById(messageId) { msg -> upsertReasoningTrace(msg, itemId, text) }
                    },
                    onRunReasoningDelta = { runId, itemId, textDelta ->
                        if (!isRunForCurrentSession(runId)) return@handleWsEvent
                        val messageId = getOrCreateRunMessageId(runId)
                        updateMessageById(messageId) { msg -> appendReasoningDelta(msg, itemId, textDelta) }
                    },
                    onDiffUpdated = { runId, threadId, files ->
                        if (!threadId.isNullOrBlank()) {
                            runToSessionId[runId] = threadId
                            if (!isEventForCurrentSession(threadId)) return@handleWsEvent
                        } else if (!isRunForCurrentSession(runId)) {
                            return@handleWsEvent
                        }

                        val messageId = getOrCreateRunMessageId(runId)
                        updateMessageById(messageId) { msg ->
                            var next = msg
                            var hasAnyDiff = false
                            files.forEach { file ->
                                next = upsertDiffTrace(next, file.path, file.diff, file.added, file.removed)
                                hasAnyDiff = true
                            }
                            if (hasAnyDiff) next.copy(isTraceExpanded = true) else next
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
            suppressAutoScroll = true
            error = null
            messages.clear()
            plan = emptyList()
            runToMessageId.clear()

            val history = api.getMessages(current)
            var traceIndex = 0
            val ui = history.map { item ->
                val (msg, nextIndex) = item.toUiMessage(traceIndex)
                traceIndex = nextIndex
                msg
            }
            messages.addAll(ui)

            val snapshot = api.getPlan(current)
            if (snapshot != null) {
                plan = snapshot.plan
            }

            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.size - 1)
            }
        } catch (ex: Exception) {
            error = ex.message ?: "加载失败"
        } finally {
            isLoading = false
            suppressAutoScroll = false
        }
    }

    // 自动滚动到底部
    LaunchedEffect(messages.size) {
        if (suppressAutoScroll) return@LaunchedEffect
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
                    ChatMessageItem(
                        message = message,
                        onToggleTrace = { messageId -> updateMessageById(messageId) { toggleTraceExpanded(it) } },
                        onToggleTraceEntry = { messageId, entryId ->
                            updateMessageById(messageId) { toggleTraceEntryExpanded(it, entryId) }
                        }
                    )
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
    val isConnecting = !isConnected && wsStatus.contains("连接中")
    val isError = !isConnected && (wsStatus.startsWith("已断开") || wsStatus.contains("失败"))

    val style = when {
        isConnected -> {
            ConnectionChipStyle(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = Icons.Default.CloudQueue,
                label = "已连接"
            )
        }
        isConnecting -> {
            ConnectionChipStyle(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                icon = Icons.Default.HourglassEmpty,
                label = "连接中"
            )
        }
        isError -> {
            ConnectionChipStyle(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                icon = Icons.Default.CloudOff,
                label = "已断开"
            )
        }
        else -> {
            ConnectionChipStyle(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                icon = Icons.Default.CloudOff,
                label = "未连接"
            )
        }
    }

    Surface(
        color = style.containerColor,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .padding(end = 8.dp)
            .semantics { contentDescription = "连接状态：$wsStatus" }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = style.label,
                modifier = Modifier.size(16.dp),
                tint = style.contentColor
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = style.label,
                style = MaterialTheme.typography.labelSmall,
                color = style.contentColor
            )
        }
    }
}

private data class ConnectionChipStyle(
    val containerColor: Color,
    val contentColor: Color,
    val icon: ImageVector,
    val label: String
)

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
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
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

private fun SessionMessage.toUiMessage(traceIndexStart: Int): Pair<ChatUiMessage, Int> {
    var traceIndex = traceIndexStart
    val entries = mutableListOf<ChatTraceEntry>()

    trace?.forEach { item ->
        traceIndex += 1
        val id = "hist_$traceIndex"
        val kind = item.kind.lowercase()

        if (kind == "reasoning") {
            val title = item.title?.trim().takeUnless { it.isNullOrBlank() } ?: "思考摘要"
            val text = item.text?.trim().orEmpty()
            entries.add(
                ChatTraceEntry(
                    id = id,
                    kind = TraceKind.Reasoning,
                    isExpanded = false,
                    reasoningRaw = text,
                    title = title,
                    text = text
                )
            )
            return@forEach
        }

        if (kind == "command") {
            val displayCommand = item.command?.takeUnless { it.isBlank() } ?: (item.tool ?: "command")
            val status = item.status?.takeUnless { it.isBlank() } ?: "completed"
            entries.add(
                ChatTraceEntry(
                    id = id,
                    kind = TraceKind.Command,
                    isExpanded = false,
                    tool = item.tool,
                    command = displayCommand,
                    status = status,
                    exitCode = item.exitCode,
                    output = item.output
                )
            )
        }
    }

    return ChatUiMessage(role = role, text = text, trace = entries) to traceIndex
}

private fun handleWsEvent(
    env: BridgeEnvelope,
    onSessionCreated: (runId: String?, sessionId: String?) -> Unit,
    onChatMessage: (role: String, text: String, runId: String?, sessionId: String?) -> Unit,
    onChatDelta: (runId: String?, delta: String) -> Unit,
    onPlanUpdated: (sessionId: String?, steps: List<TurnPlanStep>) -> Unit,
    onRunStarted: (runId: String, sessionId: String?) -> Unit,
    onRunCompleted: (runId: String, sessionId: String?, exitCode: Int?) -> Unit,
    onRunCanceled: (runId: String, sessionId: String?) -> Unit,
    onRunFailed: (runId: String, sessionId: String?, message: String?) -> Unit,
    onRunCommand: (runId: String, itemId: String, command: String, status: String?, exitCode: Int?, output: String?) -> Unit,
    onRunCommandOutputDelta: (runId: String, itemId: String, delta: String) -> Unit,
    onRunReasoning: (runId: String, itemId: String, text: String) -> Unit,
    onRunReasoningDelta: (runId: String, itemId: String, textDelta: String) -> Unit,
    onDiffUpdated: (runId: String, threadId: String?, files: List<DiffFileUpdate>) -> Unit
) {
    if (env.type.lowercase() != "event") return
    val data = env.data ?: return

    when (env.name) {
        "session.created" -> {
            val createdSessionId = data.get("sessionId")?.asString
            val runId = data.get("runId")?.asString
            onSessionCreated(runId, createdSessionId)
        }
        "run.started" -> {
            val runId = data.get("runId")?.asString ?: return
            val sessionId = data.get("sessionId")?.asString
            onRunStarted(runId, sessionId)
        }
        "run.completed" -> {
            val runId = data.get("runId")?.asString ?: return
            val sessionId = data.get("sessionId")?.asString
            val exitCode = data.get("exitCode")?.asInt
            onRunCompleted(runId, sessionId, exitCode)
        }
        "run.canceled" -> {
            val runId = data.get("runId")?.asString ?: return
            val sessionId = data.get("sessionId")?.asString
            onRunCanceled(runId, sessionId)
        }
        "run.failed" -> {
            val runId = data.get("runId")?.asString ?: return
            val sessionId = data.get("sessionId")?.asString
            val message = data.get("message")?.asString
            onRunFailed(runId, sessionId, message)
        }
        "chat.message" -> {
            val role = data.get("role")?.asString ?: return
            val text = data.get("text")?.asString ?: ""
            val runId = data.get("runId")?.asString
            val sessionId = data.get("sessionId")?.asString
            onChatMessage(role, text, runId, sessionId)
        }
        "chat.message.delta" -> {
            val delta = data.get("delta")?.asString ?: return
            val runId = data.get("runId")?.asString
            onChatDelta(runId, delta)
        }
        "run.command" -> {
            val runId = data.get("runId")?.asString ?: return
            val itemId = data.get("itemId")?.asString ?: return
            val command = data.get("command")?.asString ?: return
            val status = data.get("status")?.asString
            val exitCode = data.get("exitCode")?.asInt
            val output = data.get("output")?.asString
            onRunCommand(runId, itemId, command, status, exitCode, output)
        }
        "run.command.outputDelta" -> {
            val runId = data.get("runId")?.asString ?: return
            val itemId = data.get("itemId")?.asString ?: return
            val delta = data.get("delta")?.asString ?: return
            onRunCommandOutputDelta(runId, itemId, delta)
        }
        "run.reasoning" -> {
            val runId = data.get("runId")?.asString ?: return
            val itemId = data.get("itemId")?.asString ?: return
            val text = data.get("text")?.asString ?: return
            onRunReasoning(runId, itemId, text)
        }
        "run.reasoning.delta" -> {
            val runId = data.get("runId")?.asString ?: return
            val itemId = data.get("itemId")?.asString ?: return
            val textDelta = data.get("textDelta")?.asString ?: return
            onRunReasoningDelta(runId, itemId, textDelta)
        }
        "run.plan.updated" -> {
            val threadId = data.get("threadId")?.asString
            val planArray = data.getAsJsonArray("plan") ?: return
            val steps = parsePlanSteps(planArray)
            onPlanUpdated(threadId, steps)
        }
        "diff.updated" -> {
            val runId = data.get("runId")?.asString ?: return
            val threadId = data.get("threadId")?.asString
            val filesArray = data.getAsJsonArray("files") ?: return
            val files = parseDiffFiles(filesArray)
            if (files.isNotEmpty()) {
                onDiffUpdated(runId, threadId, files)
            }
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

private data class DiffFileUpdate(
    val path: String,
    val diff: String,
    val added: Int,
    val removed: Int
)

private fun parseDiffFiles(files: com.google.gson.JsonArray): List<DiffFileUpdate> {
    val results = mutableListOf<DiffFileUpdate>()
    for (elem in files) {
        if (!elem.isJsonObject) continue
        val obj = elem.asJsonObject
        val path = obj.get("path")?.asString ?: continue
        val diff = obj.get("diff")?.asString ?: continue
        val added = obj.get("added")?.asInt ?: 0
        val removed = obj.get("removed")?.asInt ?: 0
        results.add(DiffFileUpdate(path = path, diff = diff, added = added, removed = removed))
    }
    return results
}
