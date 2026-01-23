package com.xzh54.relayouter.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
internal fun ChatMessageItem(
    message: ChatUiMessage,
    onToggleTrace: (messageId: String) -> Unit,
    onToggleTraceEntry: (messageId: String, entryId: String) -> Unit
) {
    val isUser = message.role.equals("user", ignoreCase = true)
    if (isUser) {
        UserMessageBubble(message = message)
    } else {
        AssistantMessage(
            message = message,
            onToggleTrace = { onToggleTrace(message.id) },
            onToggleTraceEntry = { entryId -> onToggleTraceEntry(message.id, entryId) }
        )
    }
}

@Composable
private fun UserMessageBubble(message: ChatUiMessage) {
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = 16.dp,
        bottomEnd = 4.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = bubbleShape,
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            SelectionContainer {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatUiMessage,
    onToggleTrace: () -> Unit,
    onToggleTraceEntry: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.trace.isNotEmpty()) {
            TraceSection(message = message, onToggle = onToggleTrace, onToggleEntry = onToggleTraceEntry)
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (message.text.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TraceSection(
    message: ChatUiMessage,
    onToggle: () -> Unit,
    onToggleEntry: (String) -> Unit
) {
    val headerText = "执行过程（${message.trace.size}）"

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = headerText,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (message.isTraceExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null
            )
        }

        AnimatedVisibility(
            visible = message.isTraceExpanded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                message.trace.forEach { entry ->
                    TraceEntryCard(entry = entry, onToggle = { onToggleEntry(entry.id) })
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TraceEntryCard(entry: ChatTraceEntry, onToggle: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        when (entry.kind) {
            TraceKind.Command -> TraceCommand(entry = entry, onToggle = onToggle)
            TraceKind.Reasoning -> TraceReasoning(entry = entry, onToggle = onToggle)
            TraceKind.Diff -> TraceDiff(entry = entry, onToggle = onToggle)
        }
    }
}

@Composable
private fun TraceCommand(entry: ChatTraceEntry, onToggle: () -> Unit) {
    val command = entry.command.orEmpty()
    val badge = commandStatusBadge(entry.status, entry.exitCode)

    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = command,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (badge != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AnimatedVisibility(visible = entry.isExpanded && !entry.output.isNullOrBlank()) {
            val scroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(scroll)
            ) {
                SelectionContainer {
                    Text(
                        text = entry.output.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun TraceReasoning(entry: ChatTraceEntry, onToggle: () -> Unit) {
    val title = entry.title?.takeUnless { it.isBlank() } ?: "思考摘要"
    val detail = entry.text.orEmpty()

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = if (entry.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null
            )
        }

        AnimatedVisibility(visible = entry.isExpanded) {
            val scroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(scroll)
            ) {
                SelectionContainer {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun TraceDiff(entry: ChatTraceEntry, onToggle: () -> Unit) {
    val header = diffHeader(entry.filePath, entry.added, entry.removed)
    val diff = entry.diffText.orEmpty()

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = header,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = if (entry.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null
            )
        }

        AnimatedVisibility(visible = entry.isExpanded && diff.isNotBlank()) {
            val scroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(scroll)
            ) {
                SelectionContainer {
                    Text(
                        text = diff,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

