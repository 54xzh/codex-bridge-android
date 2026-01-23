package com.xzh54.relayouter.ui.screens.connect

import java.net.URI
import java.net.URLDecoder

internal data class PairingQrPayload(
    val baseUrl: String,
    val pairingCode: String
)

internal fun parsePairingQrText(text: String): PairingQrPayload? {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return null

    val uri = try {
        URI(trimmed)
    } catch (_: Exception) {
        return null
    }

    val schemeOk = uri.scheme?.equals("codex-relayouter", ignoreCase = true) == true
    val hostOk = uri.host?.equals("pair", ignoreCase = true) == true
    if (!schemeOk || !hostOk) return null

    val params = parseQueryParams(uri.rawQuery.orEmpty())
    val baseUrl = params["baseUrl"]?.trim().orEmpty()
    val pairingCode = params["pairingCode"]?.trim().orEmpty()
    if (baseUrl.isBlank() || pairingCode.isBlank()) return null

    return PairingQrPayload(baseUrl = baseUrl, pairingCode = pairingCode)
}

private fun parseQueryParams(rawQuery: String): Map<String, String> {
    if (rawQuery.isBlank()) return emptyMap()
    return rawQuery
        .split("&")
        .mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val idx = part.indexOf("=")
            val rawKey = if (idx >= 0) part.substring(0, idx) else part
            val rawValue = if (idx >= 0) part.substring(idx + 1) else ""
            val key = URLDecoder.decode(rawKey, Charsets.UTF_8.name())
            val value = URLDecoder.decode(rawValue, Charsets.UTF_8.name())
            key to value
        }
        .toMap()
}
