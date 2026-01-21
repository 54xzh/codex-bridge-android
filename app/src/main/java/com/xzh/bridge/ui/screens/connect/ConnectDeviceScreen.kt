// 本文件提供“连接设备/配对”界面骨架：输入后端地址与配对码，轮询获取 deviceToken 并回传上层保存。
package com.xzh.bridge.ui.screens.connect

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xzh.bridge.bridge.BridgeApi
import com.xzh.bridge.bridge.PairingPollResponse
import com.xzh.bridge.storage.ConnectionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectDeviceScreen(
    initialBaseUrl: String,
    onPaired: (ConnectionConfig) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var pairingCode by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(android.os.Build.MODEL ?: "Android") }
    var qrText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("请输入后端地址与配对码") }
    var isBusy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("连接设备") },
                navigationIcon = {
                    if (onCancel != null) {
                        TextButton(onClick = onCancel) { Text("返回") }
                    }
                }
            )
        }
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
        "pending" -> return false
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

