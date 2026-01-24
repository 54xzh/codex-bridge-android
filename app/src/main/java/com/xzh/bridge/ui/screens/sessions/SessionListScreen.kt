// 本文件提供"会话列表（主界面）"：展示已同步的会话列表，并可进入聊天或跳转到连接设备。
// 使用 M3 Expressive 组件和设计规范
package com.xzh54.relayouter.ui.screens.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xzh54.relayouter.bridge.BridgeApi
import com.xzh54.relayouter.bridge.BridgeEnvelope
import com.xzh54.relayouter.bridge.SessionSummary
import com.xzh54.relayouter.storage.ConnectionConfig
import kotlinx.coroutines.launch
import okhttp3.WebSocket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    api: BridgeApi,
    config: ConnectionConfig,
    onOpenSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenConnect: () -> Unit,
    onReset: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var wsStatus by remember { mutableStateOf("未连接") }
    var webSocket by remember { mutableStateOf<WebSocket?>(null) }

    val sessionRuntimeStates = remember { mutableStateMapOf<String, SessionRuntimeState>() }
    val runToSessionId = remember { mutableStateMapOf<String, String>() }

    fun updateSession(sessionId: String, transform: (SessionRuntimeState) -> SessionRuntimeState) {
        val current = sessionRuntimeStates[sessionId] ?: SessionRuntimeState()
        sessionRuntimeStates[sessionId] = transform(current)
    }

    fun applyActiveRunSnapshot(activeRuns: List<Pair<String, String>>) {
        val bySession = activeRuns.associate { it.first to it.second }
        sessionRuntimeStates.keys.toList().forEach { sessionId ->
            val existing = sessionRuntimeStates[sessionId] ?: return@forEach
            val snapshotRunId = bySession[sessionId]
            if (snapshotRunId == null && !existing.activeRunId.isNullOrBlank()) {
                sessionRuntimeStates[sessionId] = existing.copy(activeRunId = null)
            } else if (snapshotRunId != null && existing.activeRunId != snapshotRunId) {
                sessionRuntimeStates[sessionId] = reduceRunStarted(existing, snapshotRunId)
            }
        }

        bySession.forEach { (sessionId, runId) ->
            runToSessionId[runId] = sessionId
            updateSession(sessionId) { reduceRunStarted(it, runId) }
        }
    }

    fun handleWsEvent(env: BridgeEnvelope) {
        if (!env.type.equals("event", ignoreCase = true)) return
        val data = env.data ?: return

        when (env.name) {
            "run.active.snapshot" -> {
                val array = data.getAsJsonArray("activeRuns") ?: return
                val pairs = mutableListOf<Pair<String, String>>()
                for (elem in array) {
                    if (!elem.isJsonObject) continue
                    val obj = elem.asJsonObject
                    val sessionId = obj.get("sessionId")?.asString ?: continue
                    val runId = obj.get("runId")?.asString ?: continue
                    if (sessionId.isBlank() || runId.isBlank()) continue
                    pairs.add(sessionId to runId)
                }
                applyActiveRunSnapshot(pairs)
            }
            "run.started" -> {
                val runId = data.get("runId")?.asString ?: return
                val sessionId = data.get("sessionId")?.asString ?: return
                if (runId.isBlank() || sessionId.isBlank()) return
                runToSessionId[runId] = sessionId
                updateSession(sessionId) { reduceRunStarted(it, runId) }
            }
            "run.completed" -> {
                val runId = data.get("runId")?.asString ?: return
                val sessionId = data.get("sessionId")?.asString ?: runToSessionId[runId] ?: return
                updateSession(sessionId) { reduceRunCompleted(it, runId, succeeded = true) }
            }
            "run.failed" -> {
                val runId = data.get("runId")?.asString ?: return
                val sessionId = data.get("sessionId")?.asString ?: runToSessionId[runId] ?: return
                updateSession(sessionId) { reduceRunCompleted(it, runId, succeeded = false) }
            }
            "run.canceled" -> {
                val runId = data.get("runId")?.asString ?: return
                val sessionId = data.get("sessionId")?.asString ?: runToSessionId[runId] ?: return
                updateSession(sessionId) { reduceRunCanceled(it, runId) }
            }
            "run.rejected" -> {
                val sessionId = data.get("sessionId")?.asString ?: return
                updateSession(sessionId) { it.copy(activeRunId = null, hasWarningBadge = true) }
            }
        }
    }

    fun connectWs() {
        webSocket?.close(1000, "reconnect")
        wsStatus = "连接中…"
        webSocket = api.connectWebSocket(
            listener = { env -> handleWsEvent(env) },
            onClosed = { reason ->
                wsStatus = "已断开: $reason"
                webSocket = null
            }
        )
        wsStatus = "已连接"
    }

    suspend fun refresh() {
        try {
            isLoading = true
            error = null
            sessions = api.listSessions()
        } catch (ex: Exception) {
            error = ex.message ?: "加载失败"
            sessions = emptyList()
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(api.getBaseUrl()) {
        refresh()
    }

    DisposableEffect(api.getBaseUrl()) {
        connectWs()
        onDispose {
            webSocket?.close(1000, "dispose")
            webSocket = null
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SessionListTopBar(
                scrollBehavior = scrollBehavior,
                onRefresh = { scope.launch { refresh() } },
                onOpenConnect = onOpenConnect,
                onReset = onReset,
                isLoading = isLoading
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChat,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建会话"
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 连接状态卡片
            ConnectionStatusCard(
                baseUrl = api.getBaseUrl(),
                deviceId = config.deviceId,
                hasError = !error.isNullOrBlank(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 错误提示
            AnimatedVisibility(
                visible = !error.isNullOrBlank(),
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "错误: $error",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // 会话列表
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                sessions.isEmpty() && error.isNullOrBlank() -> {
                    EmptySessionsState(
                        onNewChat = onNewChat,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            val indicator = sessionRuntimeStates[session.id]?.indicator ?: SessionIndicatorKind.None
                            SessionCard(
                                session = session,
                                indicator = indicator,
                                onClick = {
                                    updateSession(session.id) { clearSessionBadges(it) }
                                    onOpenSession(session.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onRefresh: () -> Unit,
    onOpenConnect: () -> Unit,
    onReset: () -> Unit,
    isLoading: Boolean
) {
    LargeTopAppBar(
        title = {
            Text("会话")
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        actions = {
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
            IconButton(onClick = onOpenConnect) {
                Icon(Icons.Default.Settings, contentDescription = "连接设备")
            }
            IconButton(onClick = onReset) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "退出")
            }
        }
    )
}

@Composable
private fun ConnectionStatusCard(
    baseUrl: String,
    deviceId: String,
    hasError: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (hasError) Icons.Default.CloudOff else Icons.Default.Cloud,
                contentDescription = null,
                tint = if (hasError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = baseUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (deviceId.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = deviceId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SessionCard(
    session: SessionSummary,
    indicator: SessionIndicatorKind,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 会话图标
            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ChatBubbleOutline,
                    contentDescription = null
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 会话信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = session.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            SessionIndicator(indicator = indicator)
        }
    }
}

@Composable
private fun SessionIndicator(indicator: SessionIndicatorKind) {
    if (indicator == SessionIndicatorKind.None) {
        return
    }

    Spacer(modifier = Modifier.width(12.dp))

    when (indicator) {
        SessionIndicatorKind.Running -> {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
        }
        SessionIndicatorKind.Completed -> {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = androidx.compose.ui.graphics.Color(0xFF32CD32), shape = CircleShape)
            )
        }
        SessionIndicatorKind.Warning -> {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = MaterialTheme.colorScheme.tertiary, shape = CircleShape)
            )
        }
        SessionIndicatorKind.None -> Unit
    }
}

@Composable
private fun EmptySessionsState(
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无会话",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "点击右下角按钮开始新会话",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
