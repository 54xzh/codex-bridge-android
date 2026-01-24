package com.xzh54.relayouter.ui.screens.sessions

internal enum class SessionIndicatorKind {
    None,
    Running,
    Completed,
    Warning
}

internal data class SessionRuntimeState(
    val activeRunId: String? = null,
    val hasCompletedBadge: Boolean = false,
    val hasWarningBadge: Boolean = false
) {
    val indicator: SessionIndicatorKind
        get() {
            if (!activeRunId.isNullOrBlank()) return SessionIndicatorKind.Running
            if (hasWarningBadge) return SessionIndicatorKind.Warning
            if (hasCompletedBadge) return SessionIndicatorKind.Completed
            return SessionIndicatorKind.None
        }
}

internal fun reduceRunStarted(state: SessionRuntimeState, runId: String): SessionRuntimeState {
    return state.copy(activeRunId = runId, hasCompletedBadge = false, hasWarningBadge = false)
}

internal fun reduceRunCompleted(
    state: SessionRuntimeState,
    runId: String,
    succeeded: Boolean
): SessionRuntimeState {
    val cleared = if (state.activeRunId == runId) state.copy(activeRunId = null) else state
    return if (succeeded) {
        cleared.copy(hasCompletedBadge = true, hasWarningBadge = false)
    } else {
        cleared.copy(hasWarningBadge = true)
    }
}

internal fun reduceRunCanceled(state: SessionRuntimeState, runId: String): SessionRuntimeState {
    val cleared = if (state.activeRunId == runId) state.copy(activeRunId = null) else state
    return cleared.copy(hasWarningBadge = true)
}

internal fun clearSessionBadges(state: SessionRuntimeState): SessionRuntimeState {
    return state.copy(hasCompletedBadge = false, hasWarningBadge = false)
}

