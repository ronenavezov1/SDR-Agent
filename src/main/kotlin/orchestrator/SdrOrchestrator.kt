package org.example.orchestrator

import org.example.actions.CreateBookingLinkAction
import org.example.actions.DisqualifyLeadAction
import org.example.actions.EscalateToHumanAction
import org.example.actions.SendFollowUpEmailAction
import org.example.actions.SendOutreachEmailAction
import org.example.actions.UpdateQualificationAction
import org.example.agent.AgentConfig
import org.example.agent.AiAgent
import org.example.llm.LlmClient
import org.example.mock.MockBookingService
import org.example.mock.MockEmailService
import org.example.sdr.*
import org.example.tools.CheckQualificationTool
import org.example.tools.GetLeadStateTool

/**
 * SdrOrchestrator — the top-level coordinator for the AI SDR pipeline.
 *
 * ## Architecture
 *
 * ```
 *  Inbound lead / reply
 *        │
 *        ▼
 *  SdrOrchestrator
 *   ├─ LeadStore        (authoritative lead state)
 *   ├─ AuditLog         (append-only observability)
 *   ├─ MockEmailService
 *   ├─ MockBookingService
 *   └─ agentsByLead: Map<leadId, AiAgent>
 *           │
 *           ▼
 *       AiAgent (one per lead, owns its own conversation history)
 *           │  ReAct loop
 *           ├─ Tools  (read-only): getLeadState, checkQualification
 *           └─ Actions (writes):   sendOutreachEmail, sendFollowUpEmail,
 *                                  updateQualification, createBookingLink,
 *                                  escalateToHuman, disqualifyLead
 * ```
 *
 * ## State ownership
 * Lead state lives in [LeadStore], not inside the agent's history.
 * The agent reads state via tools and writes it via actions — making every
 * mutation explicit, logged, and policy-checked in code (not by prompt).
 * The agent history records the *reasoning trail* for each lead; it can be
 * summarised without losing durable lead state.
 *
 * ## Human-in-the-loop
 * When [org.example.actions.EscalateToHumanAction] is called, the lead status becomes ESCALATED_HUMAN
 * and the orchestrator's [resolveEscalation] must be called before processing resumes.
 * Other tools refuse to operate on escalated leads until the status is cleared.
 *
 * @param llmClient  The AI model backend (injectable — swap for tests).
 * @param config     Qualification rules (injectable — change without code changes).
 */
class SdrOrchestrator(
    private val llmClient: LlmClient,
    val config: QualificationConfig = QualificationConfig()
) {
    // ── Shared infrastructure ─────────────────────────────────────────────────
    val leadStore     = LeadStore()
    val auditLog      = AuditLog()
    private val emailService   = MockEmailService()
    private val bookingService = MockBookingService()

    // One AiAgent per lead — each owns its reasoning history for that lead.
    private val agentsByLead = mutableMapOf<String, AiAgent>()

    // ── Agent factory ─────────────────────────────────────────────────────────

    private fun getOrCreateAgent(leadId: String): AiAgent =
        agentsByLead.getOrPut(leadId) { buildAgent(leadId) }

    private fun buildAgent(leadId: String): AiAgent = AiAgent(
        AgentConfig(
            agentId      = "sdr-$leadId",
            llmClient    = llmClient,
            systemPrompt = buildSystemPrompt(),
            tools = mapOf(
                "getLeadState"       to GetLeadStateTool(leadStore),
                "checkQualification" to CheckQualificationTool(leadStore, config)
            ),
            actions = mapOf(
                "sendOutreachEmail"  to SendOutreachEmailAction(leadStore, emailService, auditLog),
                "sendFollowUpEmail"  to SendFollowUpEmailAction(leadStore, emailService, auditLog, config),
                "updateQualification" to UpdateQualificationAction(leadStore, auditLog),
                "createBookingLink"  to CreateBookingLinkAction(leadStore, bookingService, config, auditLog),
                "escalateToHuman"    to EscalateToHumanAction(leadStore, auditLog),
                "disqualifyLead"     to DisqualifyLeadAction(leadStore, auditLog)
            ),
            maxDepth      = 10,
            timeoutMs     = 180_000L,
            maxHistorySize = 30
        )
    )

    private fun buildSystemPrompt(): String = """
        You are an AI Sales Development Representative (SDR) for a B2B SaaS company.
        Your job: qualify inbound leads through personalised email conversation.

        ── QUALIFICATION CRITERIA ──────────────────────────────────────────────
        A lead is QUALIFIED only when ALL of the following are known and valid:
          • team_size        ≥ ${config.minTeamSize} people
          • use_case         described (what problem they want to solve)
          • commercial_intent = true  (actively evaluating, has budget intent)

        ── AVAILABLE TOOLS ─────────────────────────────────────────────────────
        READ (no side effects):
          • getLeadState(leadId)         — current lead data and qualification
          • checkQualification(leadId)   — evaluates criteria, returns QUALIFIED /
                                           NEEDS_MORE_INFO / DISQUALIFIED

        WRITE (state-changing):
          • sendOutreachEmail(leadId, subject, body)    — initial email (once only)
          • sendFollowUpEmail(leadId, subject, body)    — gather missing info
          • updateQualification(leadId, ...)            — store extracted data
          • createBookingLink(leadId)                   — for QUALIFIED leads only
          • escalateToHuman(leadId, reason, triggerMsg) — for sensitive topics
          • disqualifyLead(leadId, reason)              — for unqualified leads

        ── MANDATORY POLICIES (enforced in code — you cannot bypass them) ──────
        1. PRICING_REQUIRES_ESCALATION
           If the lead's message contains ANY mention of price, pricing, cost,
           discount, fee, budget, or "how much" — call escalateToHuman IMMEDIATELY.
           Do NOT send any email or make decisions before escalating. After
           escalating, STOP. Return your response without doing anything else.

        2. NO_DUPLICATE_OUTREACH
           Never call sendOutreachEmail more than once per lead.

        3. MAX_FOLLOW_UPS
           Never exceed the configured maximum (${config.maxFollowUps} follow-ups).

        4. BOOKING_REQUIRES_QUALIFICATION
           Never call createBookingLink unless checkQualification returns QUALIFIED.

        ── WORKFLOW ────────────────────────────────────────────────────────────
        NEW LEAD:
          1. Call getLeadState to review available info.
          2. Send a warm, personalised outreach email referencing their company/message.
          3. Ask 1–2 focused questions to gather the most critical missing info.

        REPLY RECEIVED:
          1. Scan for pricing keywords → escalate immediately if found.
          2. Call updateQualification with everything you learned from the reply.
          3. Call checkQualification to evaluate current status.
          4. Based on result:
             - QUALIFIED      → call createBookingLink, then send a warm closing email.
             - NEEDS_MORE_INFO → send a focused follow-up asking only for what's missing.
             - DISQUALIFIED   → call disqualifyLead, optionally send a polite farewell.

        POST-ESCALATION (human has responded):
          1. Use the human's guidance to decide next action.
          2. Continue the qualification flow accordingly.

        ── EMAIL STYLE ─────────────────────────────────────────────────────────
        • Professional but warm — not corporate-robotic.
        • Short (3–5 sentences for outreach, 2–3 for follow-ups).
        • Always personalise based on the lead's name, company, and context.
        • Never mention that you are an AI.
    """.trimIndent()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Register a new inbound lead and trigger the initial outreach email.
     */
    suspend fun processNewLead(lead: Lead): String {
        leadStore.save(lead)
        auditLog.log(AuditEvent(AuditEventType.LEAD_RECEIVED, lead.id,
            mapOf("name" to lead.name, "company" to lead.company, "email" to lead.email)))

        val agent = getOrCreateAgent(lead.id)
        return agent.processInput(
            """
            New inbound lead just arrived:
              ID:              ${lead.id}
              Name:            ${lead.name}
              Company:         ${lead.company}
              Email:           ${lead.email}
              Initial message: "${lead.inboundMessage}"

            Send a personalised outreach email to begin qualification.
            """.trimIndent()
        )
    }

    /**
     * Feed a lead's reply back into the agent for qualification processing.
     */
    suspend fun processReply(leadId: String, replyText: String): String {
        val lead = leadStore.get(leadId)
            ?: return "Error: Lead '$leadId' not found."

        when (lead.status) {
            LeadStatus.ESCALATED_HUMAN ->
                return "⛔ Lead is awaiting human escalation. Use: resolve $leadId"
            LeadStatus.QUALIFIED, LeadStatus.DISQUALIFIED ->
                return "⛔ Lead is already in terminal state: ${lead.status}"
            else -> {}
        }

        lead.emailThread.add(EmailMessage(EmailDirection.INBOUND, "Reply", replyText))
        auditLog.log(AuditEvent(AuditEventType.REPLY_RECEIVED, leadId,
            mapOf("preview" to replyText.take(200))))

        // Defense-in-depth: flag pricing keywords in the prompt so the model
        // is reminded of the escalation policy even if it would otherwise miss them.
        val pricingWarning = if (containsPricingKeywords(replyText))
            "\n⚠️  PRICING KEYWORDS DETECTED — you MUST call escalateToHuman immediately.\n"
        else ""

        val agent = getOrCreateAgent(leadId)
        return agent.processInput(
            """
            Reply received from ${lead.name} (${lead.company}) [leadId: $leadId]:
            "$replyText"
            $pricingWarning
            Process this reply per your workflow.
            """.trimIndent()
        )
    }

    /**
     * Provide a human reviewer's response to an escalation and resume agent processing.
     */
    suspend fun resolveEscalation(leadId: String, humanResponse: String): String {
        val lead = leadStore.get(leadId)
            ?: return "Error: Lead '$leadId' not found."

        if (lead.status != LeadStatus.ESCALATED_HUMAN)
            return "Lead '${lead.name}' is not currently escalated (status: ${lead.status})."

        lead.escalation?.humanResponse = humanResponse
        lead.status = LeadStatus.QUALIFYING

        auditLog.log(AuditEvent(AuditEventType.HUMAN_ESCALATION_RESOLVED, leadId,
            mapOf("humanResponse" to humanResponse.take(200))))

        val agent = getOrCreateAgent(leadId)
        return agent.processInput(
            """
            Human escalation resolved for ${lead.name} [leadId: $leadId].
            Human reviewer's response: "$humanResponse"

            Current lead state:
            ${lead.toContextString()}

            Continue the qualification process using the human's guidance.
            """.trimIndent()
        )
    }

    fun getLeads(): List<Lead>     = leadStore.all()
    fun getLead(id: String): Lead? = leadStore.get(id)
    fun printAuditLog(leadId: String? = null) =
        if (leadId != null) auditLog.printTimeline(leadId) else auditLog.printAllTimelines()

    private fun containsPricingKeywords(text: String): Boolean {
        val lower = text.lowercase()
        return config.pricingKeywords.any { lower.contains(it) }
    }
}
