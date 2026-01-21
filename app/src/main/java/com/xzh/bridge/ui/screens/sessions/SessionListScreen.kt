// 本文件提供“会话列表（主界面）”骨架：展示已同步的会话列表，并可进入聊天或跳转到连接设备。
package com.xzh.bridge.ui.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xzh.bridge.bridge.BridgeApi
import com.xzh.bridge.bridge.SessionSummary
import com.xzh.bridge.storage.ConnectionConfig
import kotlinx.coroutines.launch

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

    suspend fun refresh() {
        try {
            error = null
            sessions = api.listSessions()
        } catch (ex: Exception) {
            error = ex.message ?: "加载失败"
            sessions = emptyList()
        }
    }

    LaunchedEffect(api.getBaseUrl()) {
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话") },
                actions = {
                    TextButton(onClick = { scope.launch { refresh() } }) { Text("刷新") }
                    TextButton(onClick = onOpenConnect) { Text("连接设备") }
                    TextButton(onClick = onReset) { Text("退出") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChat) {
                Text("新建")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("后端: ${api.getBaseUrl()}", fontFamily = FontFamily.Monospace)
            if (config.deviceId.isNotBlank()) {
                Text("设备: ${config.deviceId}", fontFamily = FontFamily.Monospace)
            }
            if (!error.isNullOrBlank()) {
                Text("错误: $error", color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider()

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(sessions, key = { it.id }) { session ->
                    ListItem(
                        headlineContent = {
                            Text(session.title.ifBlank { "未命名会话" })
                        },
                        supportingContent = {
                            Text(session.id, fontFamily = FontFamily.Monospace)
                        },
                        trailingContent = {
                            TextButton(onClick = { onOpenSession(session.id) }) { Text("打开") }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
