package com.user.ui

import com.user.data.OrchestrationState

object OrchestrationStateRenderer {

    private val PHASE_LABELS = mapOf(
        "step_executing" to "Executing",
        "awaiting_input" to "Awaiting Input",
        "completion_repair" to "Fixing Output",
        "repair_churn_limit" to "Completion Repair Limit",
        "cancelled" to "Cancelled",
        "failed" to "Failed",
        "done" to "Complete"
    )

    private val TERMINAL_REASON_LABELS = mapOf(
        "completion_repair" to "Fixing output",
        "repair_churn_limit" to "Completion repair churn limit hit"
    )

    private val ACTION_LABELS = mapOf(
        "view_logs" to "View Logs",
        "view_timeline" to "View Timeline",
        "view_recovery_context" to "View Recovery Context",
        "start_session" to "Start",
        "pause_session" to "Pause",
        "stop_session" to "Stop",
        "resume_session" to "Resume",
        "submit_guidance" to "Submit Guidance",
        "retry_task" to "Retry Task"
    )

    fun phaseLabel(phase: String?): String =
        if (phase == null) "—" else PHASE_LABELS[phase] ?: humanize(phase)

    fun terminalReasonLabel(reason: String?): String? =
        reason?.let { TERMINAL_REASON_LABELS[it] ?: humanize(it) }

    fun actionLabel(action: String): String =
        ACTION_LABELS[action] ?: humanize(action)

    fun isOperatorPause(state: OrchestrationState): Boolean =
        "resume_session" in state.allowedActions && "submit_guidance" !in state.allowedActions

    fun isHitlWait(state: OrchestrationState): Boolean =
        "submit_guidance" in state.allowedActions

    fun stateLabel(isTerminal: Boolean): String =
        if (isTerminal) "Terminal" else "Active"

    private fun humanize(raw: String): String =
        raw.replace('_', ' ').replace('-', ' ')
            .split(' ')
            .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
}
