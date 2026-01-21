// 本文件提供 Android 端主入口 Composable，并组织三大模块页面：会话列表/聊天/连接设备。
// 路由采用 Navigation-Compose，UI 基于 Material3。
package com.xzh.bridge.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xzh.bridge.bridge.BridgeApi
import com.xzh.bridge.storage.BridgePreferences
import com.xzh.bridge.storage.ConnectionConfig
import com.xzh.bridge.ui.screens.chat.ChatScreen
import com.xzh.bridge.ui.screens.connect.ConnectDeviceScreen
import com.xzh.bridge.ui.screens.sessions.SessionListScreen

private object BridgeRoutes {
    const val Connect = "connect"
    const val Sessions = "sessions"
    const val Chat = "chat"

    const val ArgSessionId = "sessionId"
    const val ChatWithArgs = "chat?sessionId={sessionId}"

    fun chatRoute(sessionId: String?): String {
        if (sessionId.isNullOrBlank()) return Chat
        return "$Chat?$ArgSessionId=${Uri.encode(sessionId)}"
    }
}

@Composable
fun BridgeApp() {
    val context = LocalContext.current
    val prefs = remember(context) { BridgePreferences(context) }
    var config by remember { mutableStateOf(prefs.load()) }

    val navController = rememberNavController()
    val startDestination = remember {
        if (config == null) BridgeRoutes.Connect else BridgeRoutes.Sessions
    }

    val api = remember(config?.baseUrl, config?.deviceToken) {
        val current = config
        if (current == null) return@remember null
        BridgeApi(current.baseUrl) { current.deviceToken }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(BridgeRoutes.Connect) {
            ConnectDeviceScreen(
                initialBaseUrl = config?.baseUrl.orEmpty(),
                onPaired = { newConfig ->
                    prefs.save(newConfig)
                    config = newConfig
                    navController.navigate(BridgeRoutes.Sessions) {
                        popUpTo(BridgeRoutes.Connect) { inclusive = true }
                    }
                },
                onCancel = if (config != null) {
                    { navController.popBackStack() }
                } else {
                    null
                }
            )
        }

        composable(BridgeRoutes.Sessions) {
            val currentApi = api
            val currentConfig = config
            if (currentApi == null || currentConfig == null) {
                LaunchedEffect(Unit) {
                    navController.navigate(BridgeRoutes.Connect) {
                        popUpTo(BridgeRoutes.Sessions) { inclusive = true }
                    }
                }
                return@composable
            }

            SessionListScreen(
                api = currentApi,
                config = currentConfig,
                onOpenSession = { sessionId ->
                    navController.navigate(BridgeRoutes.chatRoute(sessionId))
                },
                onNewChat = {
                    navController.navigate(BridgeRoutes.chatRoute(null))
                },
                onOpenConnect = {
                    navController.navigate(BridgeRoutes.Connect)
                },
                onReset = {
                    prefs.clear()
                    config = null
                    navController.navigate(BridgeRoutes.Connect) {
                        popUpTo(BridgeRoutes.Sessions) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = BridgeRoutes.ChatWithArgs,
            arguments = listOf(
                navArgument(BridgeRoutes.ArgSessionId) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val currentApi = api
            if (currentApi == null) {
                LaunchedEffect(Unit) {
                    navController.navigate(BridgeRoutes.Connect) {
                        popUpTo(BridgeRoutes.ChatWithArgs) { inclusive = true }
                    }
                }
                return@composable
            }

            val sessionId = entry.arguments?.getString(BridgeRoutes.ArgSessionId)
            ChatScreen(
                api = currentApi,
                initialSessionId = sessionId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

