// 本文件提供"连接设备/配对"界面：通过扫码或粘贴二维码内容完成配对，轮询获取 deviceToken 并回传上层保存。
// 使用 M3 Expressive 组件和设计规范
package com.xzh.bridge.ui.screens.connect

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var baseUrl by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    val deviceName = remember { android.os.Build.MODEL ?: "Android" }
    var qrText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("请扫描或粘贴二维码内容") }
    var isBusy by remember { mutableStateOf(false) }
    var statusType by remember { mutableStateOf(StatusType.INFO) }
    var showScanner by remember { mutableStateOf(false) }

    fun applyQrText(input: String) {
        val trimmed = input.trim()
        val parsed = parsePairingQrText(trimmed)
        if (parsed == null) {
            baseUrl = ""
            pairingCode = ""
            if (trimmed.isBlank()) {
                status = "请扫描或粘贴二维码内容"
                statusType = StatusType.INFO
                return
            }
            if (!trimmed.startsWith("codex-bridge://", ignoreCase = true)) {
                status = "请粘贴 codex-bridge://pair?... 链接"
                statusType = StatusType.INFO
                return
            }

            status = "二维码内容无法解析"
            statusType = StatusType.ERROR
            return
        }
        baseUrl = parsed.baseUrl
        pairingCode = parsed.pairingCode
        status = "已解析二维码内容"
        statusType = StatusType.SUCCESS
    }

    val requestCameraPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showScanner = true
        } else {
            status = "需要相机权限才能扫码"
            statusType = StatusType.ERROR
        }
    }

    fun openScanner() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            showScanner = true
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    if (showScanner) {
        Dialog(
            onDismissRequest = { showScanner = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            QrScannerScreen(
                onScanned = { value ->
                    showScanner = false
                    qrText = value
                    applyQrText(value)
                },
                onClose = { showScanner = false }
            )
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("连接设备") },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onCancel != null) {
                        IconButton(onClick = onCancel) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
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
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 说明卡片
            InfoCard()

            // 二维码输入（唯一输入）
            InputField(
                value = qrText,
                onValueChange = { input ->
                    qrText = input
                    applyQrText(input)
                },
                label = "二维码内容",
                placeholder = "扫描或粘贴 codex-bridge://pair?... 链接",
                leadingIcon = Icons.Default.QrCode2,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    IconButton(onClick = ::openScanner) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "扫码"
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ParsedInfoCard(baseUrl = baseUrl, pairingCode = pairingCode)

            // 配对按钮
            FilledTonalButton(
                onClick = {
                    if (isBusy) return@FilledTonalButton
                    isBusy = true
                    status = "发起配对…"
                    statusType = StatusType.INFO

                    scope.launch {
                        try {
                            val api = BridgeApi(baseUrl) { null }
                            val claim = api.createPairingClaim(pairingCode, deviceName)
                            status = "等待电脑确认…"
                            statusType = StatusType.INFO

                            while (true) {
                                delay(claim.pollAfterMs.toLong().coerceAtLeast(200))
                                val poll = api.pollPairing(claim.requestId)
                                val done = handlePollResult(poll, baseUrl, onPaired) { msg, type ->
                                    status = msg
                                    statusType = type
                                }
                                if (done) {
                                    isBusy = false
                                    return@launch
                                }
                            }
                        } catch (ex: Exception) {
                            status = "配对失败: ${ex.message ?: "error"}"
                            statusType = StatusType.ERROR
                            isBusy = false
                        }
                    }
                },
                enabled = !isBusy && baseUrl.isNotBlank() && pairingCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("处理中…")
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开始配对")
                }
            }

            // 进度指示器
            AnimatedVisibility(
                visible = isBusy,
                enter = fadeIn() + slideInVertically()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
                    strokeCap = StrokeCap.Round
                )
            }

            // 状态提示
            StatusCard(
                status = status,
                type = statusType
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private enum class StatusType { INFO, SUCCESS, ERROR }

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "配对说明",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "需要在 Windows 端确认后才能完成配对；完成后会保存 deviceToken。请扫描二维码或粘贴二维码内容进行配对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    leadingIcon: ImageVector,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = trailingIcon,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        keyboardOptions = keyboardOptions,
        singleLine = true
    )
}

@Composable
private fun ParsedInfoCard(
    baseUrl: String,
    pairingCode: String
) {
    val hasParsed = baseUrl.isNotBlank() && pairingCode.isNotBlank()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        if (!hasParsed) {
            Text(
                text = "等待解析二维码内容…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return@Card
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "已解析",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "后端：$baseUrl",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "配对码：$pairingCode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: String,
    type: StatusType
) {
    val containerColor = when (type) {
        StatusType.INFO -> MaterialTheme.colorScheme.surfaceContainerLow
        StatusType.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        StatusType.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (type) {
        StatusType.INFO -> MaterialTheme.colorScheme.onSurface
        StatusType.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.padding(16.dp)
        )
    }
}

private fun handlePollResult(
    poll: PairingPollResponse,
    baseUrl: String,
    onPaired: (ConnectionConfig) -> Unit,
    setStatus: (String, StatusType) -> Unit
): Boolean {
    when (poll.status.lowercase()) {
        "pending" -> return false
        "approved" -> {
            val token = poll.deviceToken
            if (token.isNullOrBlank()) {
                setStatus("已批准，但令牌未返回（可能已领取过）。请重新生成二维码再试。", StatusType.ERROR)
                return true
            }
            val config = ConnectionConfig(
                baseUrl = baseUrl.trim(),
                deviceToken = token,
                deviceId = poll.deviceId ?: ""
            )
            setStatus("配对成功！", StatusType.SUCCESS)
            onPaired(config)
            return true
        }
        "declined" -> {
            setStatus("电脑端已拒绝该配对请求。", StatusType.ERROR)
            return true
        }
        "expired" -> {
            setStatus("配对请求已过期，请重新生成二维码。", StatusType.ERROR)
            return true
        }
        "remotedisabled" -> {
            setStatus("远程访问未启用，请在 Windows 端先开启“允许局域网连接”。", StatusType.ERROR)
            return true
        }
        else -> {
            val msg = poll.message ?: poll.status
            setStatus("配对失败: $msg", StatusType.ERROR)
            return true
        }
    }
}
