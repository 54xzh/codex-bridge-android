// 本文件封装 Android 端的连接配置持久化（baseUrl/deviceToken/deviceId）。
// 当前使用 SharedPreferences + AndroidKeyStore(GCM) 对 deviceToken 做本地加密保存。
package com.xzh.bridge.storage

import android.content.Context
import com.xzh.bridge.security.TokenCrypto

data class ConnectionConfig(
    val baseUrl: String,
    val deviceToken: String,
    val deviceId: String
)

class BridgePreferences(private val context: Context) {
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

