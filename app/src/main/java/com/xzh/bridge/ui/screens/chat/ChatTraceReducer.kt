package com.xzh54.relayouter.ui.screens.chat

internal fun toggleTraceExpanded(message: ChatUiMessage): ChatUiMessage {
    return message.copy(isTraceExpanded = !message.isTraceExpanded)
}

internal fun toggleTraceEntryExpanded(message: ChatUiMessage, entryId: String): ChatUiMessage {
    if (entryId.isBlank()) return message

    val idx = message.trace.indexOfFirst { it.id == entryId }
    if (idx < 0) return message

    val updated = message.trace.toMutableList()
    val entry = updated[idx]
    updated[idx] = entry.copy(isExpanded = !entry.isExpanded)
    return message.copy(trace = updated)
}

internal fun upsertCommandTrace(
    message: ChatUiMessage,
    itemId: String,
    tool: String?,
    command: String,
    status: String?,
    exitCode: Int?,
    output: String?
): ChatUiMessage {
    if (itemId.isBlank()) return message

    val idx = message.trace.indexOfFirst { it.id == itemId && it.kind == TraceKind.Command }
    val updated = message.trace.toMutableList()

    if (idx >= 0) {
        val existing = updated[idx]
        var next = existing.copy(command = command)

        if (!tool.isNullOrBlank()) next = next.copy(tool = tool)
        if (status != null) next = next.copy(status = status)
        if (exitCode != null) next = next.copy(exitCode = exitCode)
        if (!output.isNullOrBlank()) next = next.copy(output = output)

        updated[idx] = next
        return message.copy(trace = updated)
    }

    val resolvedStatus = status?.takeUnless { it.isBlank() } ?: "completed"
    val created = ChatTraceEntry(
        id = itemId,
        kind = TraceKind.Command,
        tool = tool?.takeUnless { it.isBlank() },
        command = command,
        status = resolvedStatus,
        exitCode = exitCode,
        output = output?.takeUnless { it.isBlank() }
    )
    updated.add(created)
    return message.copy(trace = updated)
}

internal fun appendCommandOutputDelta(message: ChatUiMessage, itemId: String, delta: String): ChatUiMessage {
    if (itemId.isBlank() || delta.isEmpty()) return message

    val idx = message.trace.indexOfFirst { it.id == itemId && it.kind == TraceKind.Command }
    val updated = message.trace.toMutableList()

    if (idx >= 0) {
        val existing = updated[idx]
        val nextOutput = if (existing.output.isNullOrEmpty()) delta else existing.output + delta
        updated[idx] = existing.copy(output = nextOutput)
        return message.copy(trace = updated)
    }

    val created = ChatTraceEntry(
        id = itemId,
        kind = TraceKind.Command,
        command = "command",
        status = "inProgress",
        output = delta
    )
    updated.add(created)
    return message.copy(trace = updated)
}

internal fun upsertReasoningTrace(message: ChatUiMessage, itemId: String, text: String): ChatUiMessage {
    if (itemId.isBlank() || text.isBlank()) return message
    return upsertReasoningInternal(message, itemId = itemId, rawText = text.trim(), mode = ReasoningUpdateMode.Replace)
}

internal fun appendReasoningDelta(message: ChatUiMessage, itemId: String, delta: String): ChatUiMessage {
    if (itemId.isBlank() || delta.isEmpty()) return message
    return upsertReasoningInternal(message, itemId = itemId, rawText = delta, mode = ReasoningUpdateMode.Append)
}

private enum class ReasoningUpdateMode {
    Replace,
    Append
}

private fun upsertReasoningInternal(
    message: ChatUiMessage,
    itemId: String,
    rawText: String,
    mode: ReasoningUpdateMode
): ChatUiMessage {
    val idx = message.trace.indexOfFirst { it.id == itemId && it.kind == TraceKind.Reasoning }
    val updated = message.trace.toMutableList()

    val next = if (idx >= 0) {
        val existing = updated[idx]
        val existingRaw = existing.reasoningRaw.orEmpty()
        val combined = when (mode) {
            ReasoningUpdateMode.Replace -> rawText
            ReasoningUpdateMode.Append -> existingRaw + rawText
        }
        val (title, detail) = splitReasoningTitle(combined)
        existing.copy(
            isExpanded = true,
            reasoningRaw = combined,
            title = title,
            text = detail
        )
    } else {
        val (title, detail) = splitReasoningTitle(rawText)
        ChatTraceEntry(
            id = itemId,
            kind = TraceKind.Reasoning,
            isExpanded = true,
            reasoningRaw = rawText,
            title = title,
            text = detail
        )
    }

    // 对齐 Windows：自动展开最新 reasoning，并折叠其他 reasoning
    for (index in updated.indices) {
        val entry = updated[index]
        if (entry.kind == TraceKind.Reasoning && entry.id != itemId && entry.isExpanded) {
            updated[index] = entry.copy(isExpanded = false)
        }
    }

    if (idx >= 0) {
        updated[idx] = next
    } else {
        updated.add(next)
    }

    return message.copy(trace = updated)
}

internal fun upsertDiffTrace(
    message: ChatUiMessage,
    path: String,
    diff: String,
    added: Int,
    removed: Int
): ChatUiMessage {
    val resolvedPath = path.trim()
    if (resolvedPath.isBlank() || diff.isBlank()) return message

    val id = "diff:$resolvedPath"
    val idx = message.trace.indexOfFirst { it.id == id && it.kind == TraceKind.Diff }
    val updated = message.trace.toMutableList()

    if (idx >= 0) {
        val existing = updated[idx]
        updated[idx] = existing.copy(
            isExpanded = true,
            filePath = resolvedPath,
            diffText = diff,
            added = added,
            removed = removed
        )
        return message.copy(trace = updated)
    }

    val created = ChatTraceEntry(
        id = id,
        kind = TraceKind.Diff,
        isExpanded = true,
        filePath = resolvedPath,
        diffText = diff,
        added = added,
        removed = removed
    )
    updated.add(created)
    return message.copy(trace = updated)
}

