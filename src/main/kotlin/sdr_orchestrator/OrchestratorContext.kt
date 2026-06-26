package org.example.sdr_orchestrator

import org.example.llm.LlmClient
import org.example.sdr.Event
import org.example.sdr.Lead
import org.example.sdr.QualificationConfig

/**
 * Everything an [SdrOrchestrator] (and its Actions/Tools) needs — scoped to a single lead.
 *
 * Declared here (in the orchestrator package) so the orchestrator owns its own contract.
 * [org.example.repositiories.SdrRepository] provides the concrete implementation.
 *
 * Principle of least privilege:
 *  - No access to other leads — [getLead] always returns the current lead.
 *  - No direct access to [org.example.repositiories.SdrRepository], [org.example.repositiories.EmailRepository], or raw [LlmClient] constructor.
 *  - Actions and Tools receive this same context — they cannot affect leads outside their scope.
 */
interface OrchestratorContext {

    // ── Identity ──────────────────────────────────────────────────────────────

    val leadEmail: String

    // ── Infrastructure ────────────────────────────────────────────────────────

    val llmClient: LlmClient
    val config: QualificationConfig

    // ── Lead data (scoped to leadEmail) ───────────────────────────────────────

    fun getLead(): Lead?
    fun saveLead(lead: Lead)
    suspend fun logEvent(event: Event)
    suspend fun emitResult(text: String)

    // ── Email & booking infrastructure ────────────────────────────────────────

    fun sendEmail(to: String, subject: String, body: String): String
    fun createBookingLink(leadName: String, company: String): String
    fun createClientAskBookingLink(): String
    fun createSalesTeamAskBookingLink(): String
}
