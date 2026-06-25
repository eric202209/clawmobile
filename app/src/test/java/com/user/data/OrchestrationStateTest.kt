package com.user.data

import com.user.ui.OrchestrationStateRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestrationStateTest {

    private fun state(
        currentPhase: String? = "step_executing",
        terminalReason: String? = null,
        coordinator: String? = null,
        isTerminal: Boolean = false,
        allowedActions: List<String> = listOf("view_logs", "view_timeline")
    ) = OrchestrationState(
        currentPhase = currentPhase,
        terminalReason = terminalReason,
        coordinator = coordinator,
        isTerminal = isTerminal,
        allowedActions = allowedActions
    )

    // ── Model defaults ────────────────────────────────────────────────────────

    @Test
    fun modelDefaults_areNullSafe() {
        val s = OrchestrationState()
        assertNull(s.currentPhase)
        assertNull(s.terminalReason)
        assertNull(s.coordinator)
        assertFalse(s.isTerminal)
        assertTrue(s.allowedActions.isEmpty())
    }

    @Test
    fun missingOrchestrationState_isToleratedAsNull() {
        // MobileSessionSummaryResponse with no orchestration_state field defaults to null
        val summary = MobileSessionSummaryResponse(sessionId = 1, name = "test", status = "running")
        assertNull(summary.orchestrationState)
    }

    // ── Phase label rendering ─────────────────────────────────────────────────

    @Test
    fun phaseLabel_stepExecuting() =
        assertEquals("Executing", OrchestrationStateRenderer.phaseLabel("step_executing"))

    @Test
    fun phaseLabel_awaitingInput() =
        assertEquals("Awaiting Input", OrchestrationStateRenderer.phaseLabel("awaiting_input"))

    @Test
    fun phaseLabel_cancelled() =
        assertEquals("Cancelled", OrchestrationStateRenderer.phaseLabel("cancelled"))

    @Test
    fun phaseLabel_failed() =
        assertEquals("Failed", OrchestrationStateRenderer.phaseLabel("failed"))

    @Test
    fun phaseLabel_done() =
        assertEquals("Complete", OrchestrationStateRenderer.phaseLabel("done"))

    @Test
    fun phaseLabel_nullReturnsEmDash() =
        assertEquals("—", OrchestrationStateRenderer.phaseLabel(null))

    @Test
    fun phaseLabel_unknownPhaseHumanizes() =
        assertEquals("Custom Phase X", OrchestrationStateRenderer.phaseLabel("custom_phase_x"))

    // ── Terminal reason rendering ─────────────────────────────────────────────

    @Test
    fun terminalReasonLabel_repairChurnLimit() =
        assertEquals(
            "Completion repair churn limit hit",
            OrchestrationStateRenderer.terminalReasonLabel("repair_churn_limit")
        )

    @Test
    fun terminalReasonLabel_completionRepair() =
        assertEquals(
            "Fixing output",
            OrchestrationStateRenderer.terminalReasonLabel("completion_repair")
        )

    @Test
    fun terminalReasonLabel_nullReturnsNull() =
        assertNull(OrchestrationStateRenderer.terminalReasonLabel(null))

    @Test
    fun terminalReasonLabel_unknownHumanizes() =
        assertEquals(
            "Some Unknown Reason",
            OrchestrationStateRenderer.terminalReasonLabel("some_unknown_reason")
        )

    // ── Coordinator rendering ────────────────────────────────────────────────

    @Test
    fun coordinator_renderedWhenPresent() {
        val s = state(coordinator = "ExecutionCoordinator")
        assertEquals("ExecutionCoordinator", s.coordinator)
    }

    @Test
    fun coordinator_nullWhenAbsent() {
        val s = state(coordinator = null)
        assertNull(s.coordinator)
    }

    // ── Active / Terminal state label ────────────────────────────────────────

    @Test
    fun stateLabel_activeForNonTerminal() =
        assertEquals("Active", OrchestrationStateRenderer.stateLabel(false))

    @Test
    fun stateLabel_terminalForTerminal() =
        assertEquals("Terminal", OrchestrationStateRenderer.stateLabel(true))

    // ── Allowed actions rendering ─────────────────────────────────────────────

    @Test
    fun actionLabel_viewLogs() =
        assertEquals("View Logs", OrchestrationStateRenderer.actionLabel("view_logs"))

    @Test
    fun actionLabel_resumeSession() =
        assertEquals("Resume", OrchestrationStateRenderer.actionLabel("resume_session"))

    @Test
    fun actionLabel_submitGuidance() =
        assertEquals("Submit Guidance", OrchestrationStateRenderer.actionLabel("submit_guidance"))

    @Test
    fun actionLabel_unknownHumanizes() =
        assertEquals("New Action", OrchestrationStateRenderer.actionLabel("new_action"))

    @Test
    fun allowedActions_parsedFromModel() {
        val s = state(allowedActions = listOf("view_logs", "pause_session", "stop_session"))
        assertEquals(3, s.allowedActions.size)
        assertTrue("view_logs" in s.allowedActions)
        assertTrue("pause_session" in s.allowedActions)
        assertTrue("stop_session" in s.allowedActions)
    }

    @Test
    fun allowedActions_emptyListIsValid() {
        val s = state(allowedActions = emptyList())
        assertTrue(s.allowedActions.isEmpty())
    }

    // ── Paused vs HITL distinction via allowed_actions ────────────────────────

    @Test
    fun isOperatorPause_trueWhenResumeSessionPresent() {
        val s = state(
            currentPhase = "awaiting_input",
            allowedActions = listOf("view_logs", "view_timeline", "resume_session", "stop_session")
        )
        assertTrue(OrchestrationStateRenderer.isOperatorPause(s))
        assertFalse(OrchestrationStateRenderer.isHitlWait(s))
    }

    @Test
    fun isHitlWait_trueWhenSubmitGuidancePresent() {
        val s = state(
            currentPhase = "awaiting_input",
            allowedActions = listOf("view_logs", "view_timeline", "submit_guidance", "stop_session")
        )
        assertTrue(OrchestrationStateRenderer.isHitlWait(s))
        assertFalse(OrchestrationStateRenderer.isOperatorPause(s))
    }

    @Test
    fun pausedVsHitl_drivenByAllowedActionsNotCurrentPhase() {
        // Both paused and HITL have current_phase = "awaiting_input"
        val pausedState = state(
            currentPhase = "awaiting_input",
            allowedActions = listOf("resume_session")
        )
        val hitlState = state(
            currentPhase = "awaiting_input",
            allowedActions = listOf("submit_guidance")
        )
        assertTrue(OrchestrationStateRenderer.isOperatorPause(pausedState))
        assertFalse(OrchestrationStateRenderer.isOperatorPause(hitlState))
        assertTrue(OrchestrationStateRenderer.isHitlWait(hitlState))
        assertFalse(OrchestrationStateRenderer.isHitlWait(pausedState))
    }

    @Test
    fun unknownPhase_treatedAsNonTerminal() {
        // Contract rule: treat unknown current_phase as non-terminal / informational
        val s = state(currentPhase = "future_unknown_phase", isTerminal = false)
        assertFalse(s.isTerminal)
        assertEquals("Future Unknown Phase", OrchestrationStateRenderer.phaseLabel(s.currentPhase))
    }

    // ── Button gating via allowed_actions (Phase 14E) ─────────────────────────

    @Test
    fun allowedActions_gatePauseButton_presentWhenIncluded() {
        val s = state(allowedActions = listOf("pause_session", "stop_session"))
        assertTrue("pause_session" in s.allowedActions)
    }

    @Test
    fun allowedActions_gatePauseButton_absentWhenExcluded() {
        val s = state(allowedActions = listOf("stop_session"))
        assertFalse("pause_session" in s.allowedActions)
    }

    @Test
    fun allowedActions_gateResumeButton_presentWhenIncluded() {
        val s = state(allowedActions = listOf("resume_session", "stop_session"))
        assertTrue("resume_session" in s.allowedActions)
    }

    @Test
    fun allowedActions_gateResumeButton_absentWhenExcluded() {
        val s = state(allowedActions = listOf("stop_session", "view_logs"))
        assertFalse("resume_session" in s.allowedActions)
    }

    @Test
    fun allowedActions_gateStopButton_presentWhenIncluded() {
        val s = state(allowedActions = listOf("pause_session", "stop_session"))
        assertTrue("stop_session" in s.allowedActions)
    }

    @Test
    fun allowedActions_gateStopButton_absentWhenExcluded() {
        val s = state(allowedActions = listOf("pause_session", "view_logs"))
        assertFalse("stop_session" in s.allowedActions)
    }

    @Test
    fun allowedActions_gateSubmitGuidance_presentForHitl() {
        val s = state(
            currentPhase = "awaiting_input",
            allowedActions = listOf("submit_guidance", "stop_session")
        )
        assertTrue("submit_guidance" in s.allowedActions)
    }

    @Test
    fun allowedActions_gateSubmitGuidance_absentForOperatorPause() {
        val s = state(
            currentPhase = "awaiting_input",
            allowedActions = listOf("resume_session", "stop_session")
        )
        assertFalse("submit_guidance" in s.allowedActions)
    }

    @Test
    fun nullOrchestrationState_allowedActionsIsNull_fallsBackToStatusBased() {
        // When orchestrationState is null, allowed_actions check returns null → status-based fallback
        val summary = MobileSessionSummaryResponse(sessionId = 1, name = "test", status = "running")
        assertNull(summary.orchestrationState?.allowedActions)
    }

    @Test
    fun runningSession_typicalAllowedActions() {
        val s = state(
            currentPhase = "step_executing",
            allowedActions = listOf("pause_session", "stop_session", "view_logs", "view_timeline")
        )
        assertTrue("pause_session" in s.allowedActions)
        assertTrue("stop_session" in s.allowedActions)
        assertFalse("resume_session" in s.allowedActions)
        assertFalse("submit_guidance" in s.allowedActions)
    }

    @Test
    fun pausedSession_typicalAllowedActions() {
        val s = state(
            currentPhase = "awaiting_input",
            allowedActions = listOf("resume_session", "stop_session", "view_logs", "view_timeline")
        )
        assertTrue("resume_session" in s.allowedActions)
        assertTrue("stop_session" in s.allowedActions)
        assertFalse("pause_session" in s.allowedActions)
        assertFalse("submit_guidance" in s.allowedActions)
    }

    @Test
    fun terminalSession_noMutatingActions() {
        val s = state(
            currentPhase = "done",
            isTerminal = true,
            allowedActions = listOf("view_logs", "view_timeline", "view_recovery_context")
        )
        assertFalse("pause_session" in s.allowedActions)
        assertFalse("resume_session" in s.allowedActions)
        assertFalse("stop_session" in s.allowedActions)
        assertFalse("submit_guidance" in s.allowedActions)
    }
}
