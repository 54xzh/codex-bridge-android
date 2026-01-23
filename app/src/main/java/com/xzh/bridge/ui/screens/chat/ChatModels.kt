package com.xzh54.relayouter.ui.screens.chat

import java.util.UUID

internal enum class TraceKind {
    Command,
    Reasoning,
    Diff
}

internal data class ChatTraceEntry(
    val id: String,
    val kind: TraceKind,
    val isExpanded: Boolean = false,
    val reasoningRaw: String? = null,
    val title: String? = null,
    val text: String? = null,
    val tool: String? = null,
    val command: String? = null,
    val status: String? = null,
    val exitCode: Int? = null,
    val output: String? = null,
    val filePath: String? = null,
    val diffText: String? = null,
    val added: Int = 0,
    val removed: Int = 0
)

internal data class ChatUiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val runId: String? = null,
    val trace: List<ChatTraceEntry> = emptyList(),
    val isTraceExpanded: Boolean = false
)

internal fun splitReasoningTitle(raw: String?): Pair<String, String> {
    val detail = raw?.trim().orEmpty()
    if (detail.isBlank()) {
        return "思考摘要" to ""
    }

    if (detail.startsWith("**")) {
        val end = detail.indexOf("**", startIndex = 2)
        if (end > 2) {
            val extractedTitle = detail.substring(2, end).trim()
            val rest = detail.substring(end + 2).trim()
            val title = if (extractedTitle.isBlank()) "思考摘要" else extractedTitle
            val text = if (rest.isBlank()) detail else rest
            return title to text
        }
    }

    val firstLine = detail.lineSequence().firstOrNull()?.trim().orEmpty()
    if (firstLine.isBlank()) {
        return "思考摘要" to detail
    }

    val title = if (firstLine.length <= 80) firstLine else firstLine.take(79) + "…"
    return title to detail
}

internal fun commandStatusBadge(status: String?, exitCode: Int?): String? {
    val normalizedStatus = status?.trim().takeUnless { it.isNullOrBlank() } ?: "completed"

    if (normalizedStatus.equals("completed", ignoreCase = true)
        && (exitCode == null || exitCode == 0)
    ) {
        return null
    }

    if (exitCode == null) {
        return normalizedStatus
    }

    if (normalizedStatus.equals("completed", ignoreCase = true)) {
        return "exitCode=$exitCode"
    }

    return "$normalizedStatus exitCode=$exitCode".trim()
}

internal fun diffHeader(filePath: String?, added: Int, removed: Int): String {
    val name = filePath?.takeUnless { it.isBlank() } ?: "变更"
    return "$name (+$added -$removed)"
}

