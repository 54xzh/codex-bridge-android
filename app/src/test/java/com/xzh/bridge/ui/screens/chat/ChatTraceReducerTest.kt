package com.xzh54.relayouter.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTraceReducerTest {
    @Test
    fun splitReasoningTitle_nullInput_returnsDefaultTitleAndEmptyText() {
        val (title, text) = splitReasoningTitle(null)
        assertEquals("思考摘要", title)
        assertEquals("", text)
    }

    @Test
    fun splitReasoningTitle_markdownTitle_extractsTitleAndBody() {
        val (title, text) = splitReasoningTitle("**标题**\n\n这里是正文")
        assertEquals("标题", title)
        assertEquals("这里是正文", text)
    }

    @Test
    fun splitReasoningTitle_longFirstLine_truncatesTo80() {
        val firstLine = "a".repeat(81)
        val (title, text) = splitReasoningTitle("$firstLine\nsecond")
        assertEquals(80, title.length)
        assertTrue(title.endsWith("…"))
        assertEquals(firstLine.take(79) + "…", title)
        assertEquals("$firstLine\nsecond".trim(), text)
    }

    @Test
    fun commandStatusBadge_completedWithZeroOrNullExitCode_returnsNull() {
        assertNull(commandStatusBadge("completed", null))
        assertNull(commandStatusBadge("completed", 0))
    }

    @Test
    fun commandStatusBadge_nonCompletedOrNonZeroExitCode_returnsBadge() {
        assertEquals("in_progress", commandStatusBadge("in_progress", null))
        assertEquals("exitCode=1", commandStatusBadge("completed", 1))
        assertEquals("failed exitCode=2", commandStatusBadge("failed", 2))
    }

    @Test
    fun upsertReasoningTrace_expandsLatestAndCollapsesPrevious() {
        val base = ChatUiMessage(role = "assistant", text = "x")
        val first = upsertReasoningTrace(base, itemId = "r1", text = "First reasoning")
        val second = upsertReasoningTrace(first, itemId = "r2", text = "Second reasoning")

        val r1 = second.trace.firstOrNull { it.id == "r1" }
        val r2 = second.trace.firstOrNull { it.id == "r2" }
        assertNotNull(r1)
        assertNotNull(r2)
        assertFalse(r1!!.isExpanded)
        assertTrue(r2!!.isExpanded)
    }

    @Test
    fun appendCommandOutputDelta_missingCommand_createsPlaceholder() {
        val base = ChatUiMessage(role = "assistant", text = "x")
        val next = appendCommandOutputDelta(base, itemId = "c1", delta = "hello\n")

        val cmd = next.trace.firstOrNull { it.id == "c1" }
        assertNotNull(cmd)
        assertEquals(TraceKind.Command, cmd!!.kind)
        assertEquals("command", cmd.command)
        assertEquals("inProgress", cmd.status)
        assertEquals("hello\n", cmd.output)
    }
}

