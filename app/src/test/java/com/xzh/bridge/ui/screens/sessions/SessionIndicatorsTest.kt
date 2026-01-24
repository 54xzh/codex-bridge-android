package com.xzh54.relayouter.ui.screens.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionIndicatorsTest {
    @Test
    fun indicator_priority_running_overridesBadges() {
        val state = SessionRuntimeState(activeRunId = "r1", hasCompletedBadge = true, hasWarningBadge = true)
        assertEquals(SessionIndicatorKind.Running, state.indicator)
    }

    @Test
    fun indicator_warning_overridesCompleted() {
        val state = SessionRuntimeState(hasCompletedBadge = true, hasWarningBadge = true)
        assertEquals(SessionIndicatorKind.Warning, state.indicator)
    }

    @Test
    fun reduceRunStarted_setsActiveRunAndClearsBadges() {
        val initial = SessionRuntimeState(hasCompletedBadge = true, hasWarningBadge = true)
        val next = reduceRunStarted(initial, runId = "r1")
        assertEquals("r1", next.activeRunId)
        assertEquals(SessionIndicatorKind.Running, next.indicator)
        assertEquals(false, next.hasCompletedBadge)
        assertEquals(false, next.hasWarningBadge)
    }

    @Test
    fun reduceRunCompleted_succeeded_clearsActiveRunAndSetsCompleted() {
        val initial = SessionRuntimeState(activeRunId = "r1")
        val next = reduceRunCompleted(initial, runId = "r1", succeeded = true)
        assertNull(next.activeRunId)
        assertEquals(true, next.hasCompletedBadge)
        assertEquals(SessionIndicatorKind.Completed, next.indicator)
    }

    @Test
    fun reduceRunCanceled_clearsActiveRunAndSetsWarning() {
        val initial = SessionRuntimeState(activeRunId = "r1")
        val next = reduceRunCanceled(initial, runId = "r1")
        assertNull(next.activeRunId)
        assertEquals(true, next.hasWarningBadge)
        assertEquals(SessionIndicatorKind.Warning, next.indicator)
    }
}

