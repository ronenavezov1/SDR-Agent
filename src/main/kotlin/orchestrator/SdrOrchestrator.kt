package org.example.orchestrator

import org.example.actions.*
import org.example.agent.AgentConfig
import org.example.agent.AiAgent
import org.example.llm.LlmClient
import org.example.mock.MockBookingService
import org.example.mock.MockEmailService
import org.example.repositiories.SdrRepository
import org.example.sdr.*
import org.example.tools.CheckQualificationTool
import org.example.tools.GetLeadStateTool

/**
 * SdrOrchestrator — stateless multi-agent coordinator.
 *
 * Contains six specialised [AiAgent]s, each responsible for exactly one step
 * of the qualification pipeline.  Every agent clears its own history after
 * each call, so every invocation starts from a clean slate — all context
 * is carried by the [Lead]'s event list (stored in [SdrRepository]).
 *
 * Pipeline:
 *  processNewLead  →  outreachWriter
 *  processReply    →  escalationDetector
 *                  →  qualificationExtractor
 *                  →  infoSufficiency
 *                  →  dealDecision  |  followUpWriter
 *  resolveEscalation →  infoSufficiency  →  dealDecision  |  followUpWriter
 */
class SdrOrchestrator(
    private val llmClient:  LlmClient,
    private val repository: SdrRepository,
    private val config:     QualificationConfig
) {
    private val emailService   = MockEmailService()
    private val bookingService = MockBookingService()

    // ── Agent pool ────────────────────────────────────────────────────────────

    // Direct-send action instances (invoked by the orchestrator after reviewer approval)
    private val sendOutreachAction  = SendOutreachEmailAction(repository, emailService)
    private val sendFollowUpAction  = SendFollowUpEmailAction(repository, emailService, config)

    /**
     * Drafts the very first outreach email — does NOT send.
     * Output format (required):
     *   SUBJECT: <subject line>
     *   BODY:
     *   <full email body>
     * Tools: getLeadState  |  Actions: none
     */
    private val outreachWriter = AiAgent(AgentConfig(
        agentId      = "outreach-writer",
        llmClient    = llmClient,
        systemPrompt = """
            You are an SDR email writer. Your only job: compose one personalised outreach email for a new lead.
            Call getLeadState first to understand the lead.
            Then output the draft — do NOT call any send action.

            Style: professional and warm, 3–5 sentences, ask 1–2 targeted qualification questions.
            Never reveal you are an AI. Never use placeholder text like [Your Name] or [Company].
            Sign the email as "The Sales Team".

            Your ENTIRE response must be in this exact format (nothing else):
            SUBJECT: <subject line>
            BODY:
            <full email body>
        """.trimIndent(),
        tools   = mapOf("getLeadState" to GetLeadStateTool(repository)),
        actions = emptyMap(),
        maxDepth = 4, timeoutMs = 60_000L, maxHistorySize = 15
    ))

    /**
     * Drafts a follow-up email — does NOT send.
     * Output format (required):
     *   SUBJECT: <subject line>
     *   BODY:
     *   <full email body>
     * Tools: getLeadState  |  Actions: none
     */
    private val followUpWriter = AiAgent(AgentConfig(
        agentId      = "followup-writer",
        llmClient    = llmClient,
        systemPrompt = """
            You write targeted follow-up emails to gather missing qualification information.
            Call getLeadState to see what is already known and what is still missing.
            Then output the draft — do NOT call any send action.

            Style: concise (2–3 sentences). Ask exactly ONE focused question about the most important missing field.
            Never reveal you are an AI. Never use placeholder text like [Your Name] or [Company].
            Sign the email as "The Sales Team".

            Your ENTIRE response must be in this exact format (nothing else):
            SUBJECT: <subject line>
            BODY:
            <full email body>
        """.trimIndent(),
        tools   = mapOf("getLeadState" to GetLeadStateTool(repository)),
        actions = emptyMap(),
        maxDepth = 3, timeoutMs = 45_000L, maxHistorySize = 15
    ))

    /**
     * Reviews an email draft before it is sent.
     * Returns exactly: APPROVED  or  REJECTED: <reason>
     * No tools or actions — pure reasoning only.
     */
    private val emailReviewer = AiAgent(AgentConfig(
        agentId      = "email-reviewer",
        llmClient    = llmClient,
        systemPrompt = """
            You are an email quality reviewer for an SDR team.
            You receive a draft email and decide whether it is ready to send to a real lead.

            REJECT (respond with "REJECTED: <specific reason>") if the email:
              • Contains any unfilled placeholder text: [Your Name], [Company], [Title], [Link], [Date], etc.
              • Uses generic filler that shows zero personalisation.
              • Asks more than one question (follow-ups must have exactly ONE question).
              • Contains pricing, discount, or contract terms — the SDR must not discuss those.
              • Has an unprofessional tone or obvious grammatical errors.

            APPROVE (respond with exactly "APPROVED") if the email is:
              • Specific, personalised, warm, and professional.
              • Free of placeholders or template artefacts.
              • Ready to be delivered to a real person.

            Respond with ONLY "APPROVED" or "REJECTED: <reason>". No other text.
        """.trimIndent(),
        tools    = emptyMap(),
        actions  = emptyMap(),
        maxDepth = 2, timeoutMs = 20_000L, maxHistorySize = 5
    ))

    /**
     * Scans an incoming message for escalation triggers (pricing, discounts, legal, sensitive topics).
     * If a trigger is found → calls escalateToHuman, then responds: ESCALATED
     * If no trigger found  → responds: SAFE
     * Tools: getLeadState  |  Actions: escalateToHuman
     */
    private val escalationDetector = AiAgent(AgentConfig(
        agentId      = "escalation-detector",
        llmClient    = llmClient,
        systemPrompt = """
            You analyse incoming lead messages for escalation triggers.
            Escalation triggers: pricing, discounts, costs, budget negotiation, legal/contract terms,
            competitor comparisons, or any commercially sensitive topic.

            If a trigger is detected:
              1. Call escalateToHuman with a clear reason and the triggering quote.
              2. End your response with exactly: ESCALATED

            If no trigger is detected:
              Respond with exactly: SAFE

            Do not add any other text after ESCALATED or SAFE.
        """.trimIndent(),
        tools   = mapOf("getLeadState" to GetLeadStateTool(repository)),
        actions = mapOf("escalateToHuman" to EscalateToHumanAction(repository)),
        maxDepth = 3, timeoutMs = 30_000L, maxHistorySize = 10
    ))

    /**
     * Extracts structured qualification fields from an incoming reply and persists them.
     * Rules: only save fields that are clearly and explicitly stated — leave null if uncertain.
     * Tools: getLeadState  |  Actions: updateQualification
     */
    private val qualificationExtractor = AiAgent(AgentConfig(
        agentId      = "qualification-extractor",
        llmClient    = llmClient,
        systemPrompt = """
            You extract qualification data from a lead's incoming email reply.
            Call getLeadState first to see what is already known.
            Then call updateQualification with ONLY the fields you are confident about:

              • useCase          — save ONLY if the lead clearly describes their specific problem or need.
                                   If vague, generic, or unclear → omit (leave null).
              • teamSize         — save ONLY if an explicit number or range is stated.
              • commercialIntent — set to true ONLY if they mention active budget, evaluation process,
                                   or purchasing intent. Set to false if they explicitly say no intent.
                                   If ambiguous → omit.

            Do NOT guess. Omit any field you are not certain about.
        """.trimIndent(),
        tools   = mapOf("getLeadState" to GetLeadStateTool(repository)),
        actions = mapOf("updateQualification" to UpdateQualificationAction(repository)),
        maxDepth = 3, timeoutMs = 30_000L, maxHistorySize = 10
    ))

    /**
     * Assesses whether current qualification data is sufficient to make a final deal decision,
     * taking into account the remaining follow-up budget.
     * Returns exactly one token: DECIDE_NOW | NEEDS_MORE_INFO | DISQUALIFY_NOW
     * Tools: getLeadState, checkQualification  |  Actions: none
     */
    private val infoSufficiency = AiAgent(AgentConfig(
        agentId      = "info-sufficiency",
        llmClient    = llmClient,
        systemPrompt = """
            You decide whether enough qualification data has been collected to make a final deal decision,
            given the remaining follow-up message budget.

            Steps: call getLeadState and checkQualification, then respond with exactly ONE of:

              DISQUALIFY_NOW  — team_size is below the minimum OR commercial_intent is explicitly false.
              DECIDE_NOW      — all three fields (use_case, team_size, commercial_intent) are known,
                                OR remaining follow-ups = 0 (force a decision with whatever data exists).
              NEEDS_MORE_INFO — at least one field is still missing AND follow-up budget remains.

            Respond with ONLY the token. No explanation, no extra text.
        """.trimIndent(),
        tools   = mapOf(
            "getLeadState"       to GetLeadStateTool(repository),
            "checkQualification" to CheckQualificationTool(repository, config)
        ),
        actions  = emptyMap(),
        maxDepth = 4, timeoutMs = 30_000L, maxHistorySize = 15
    ))

    /**
     * Makes the final QUALIFIED / DISQUALIFIED decision and acts on it.
     * QUALIFIED   → createBookingLink + write closing email text.
     * DISQUALIFIED → disqualifyLead + write polite farewell text.
     * Tools: getLeadState, checkQualification  |  Actions: createBookingLink, disqualifyLead
     */
    private val dealDecision = AiAgent(AgentConfig(
        agentId      = "deal-decision",
        llmClient    = llmClient,
        systemPrompt = """
            You make the final deal decision for a lead and act on it immediately.
            Steps: call getLeadState then checkQualification to assess the current data.

            If QUALIFIED:
              1. Call createBookingLink.
              2. Write a warm, professional closing message (plain text, not a tool call)
                 that includes the booking link and next steps.

            If DISQUALIFIED:
              1. Call disqualifyLead with a specific, factual reason.
              2. Write a brief, polite farewell message.

            Always reach a conclusion — never leave a lead without a final action.
        """.trimIndent(),
        tools = mapOf(
            "getLeadState"       to GetLeadStateTool(repository),
            "checkQualification" to CheckQualificationTool(repository, config)
        ),
        actions = mapOf(
            "createBookingLink" to CreateBookingLinkAction(repository, bookingService, config),
            "disqualifyLead"    to DisqualifyLeadAction(repository)
        ),
        maxDepth = 5, timeoutMs = 60_000L, maxHistorySize = 20
    ))

    // ── Draft helpers ─────────────────────────────────────────────────────────

    /**
     * Parses the writer agent's structured output into (subject, body).
     * Expected format:
     *   SUBJECT: <subject line>
     *   BODY:
     *   <full email body>
     */
    private fun parseDraft(text: String): Pair<String, String>? {
        val lines = text.lines()
        val subjectLine = lines.firstOrNull { it.trimStart().startsWith("SUBJECT:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: return null
        val bodyIdx = lines.indexOfFirst { it.trimStart().startsWith("BODY:", ignoreCase = true) }
        if (bodyIdx < 0) return null
        val body = lines.drop(bodyIdx + 1).joinToString("\n").trim()
        if (body.isEmpty()) return null
        return Pair(subjectLine, body)
    }

    /**
     * Writer ↔ Reviewer loop — bounded by [QualificationConfig.maxEmailDraftRetries].
     *
     * The limit is a *configurable policy* (not a magic number): it represents the business
     * decision of how many LLM round-trips are acceptable before an imperfect email is still
     * preferred over no email at all.  Zero means "send the first draft regardless of quality".
     *
     * @return Approved (subject, body) pair, or the best available draft if retries are exhausted.
     */
    private suspend fun writeDraftWithReview(
        writer:       AiAgent,
        writerPrompt: String,
        leadEmail:    String
    ): Pair<String, String> {
        var feedback    = ""
        var lastSubject = ""
        var lastBody    = ""

        repeat(config.maxEmailDraftRetries) { attempt ->
            val prompt = if (feedback.isEmpty()) writerPrompt
                         else "$writerPrompt\n\n⚠️ REVIEWER FEEDBACK (attempt ${attempt + 1}/${config.maxEmailDraftRetries}): $feedback\n" +
                              "Fix ALL mentioned issues before outputting the revised draft."

            val draftText = writer.processInput(prompt)
            val parsed    = parseDraft(draftText)

            if (parsed == null) {
                feedback = "Output was not in required format. Use exactly:\nSUBJECT: <line>\nBODY:\n<text>"
                return@repeat
            }

            val (subject, body) = parsed
            lastSubject = subject
            lastBody    = body

            val reviewInput  = "Review this email draft for lead <$leadEmail>:\n\nSUBJECT: $subject\nBODY:\n$body"
            val reviewResult = emailReviewer.processInput(reviewInput).trim()

            if (reviewResult.startsWith("APPROVED")) {
                return Pair(subject, body)
            }
            feedback = reviewResult.removePrefix("REJECTED:").trim()
        }

        // Retries exhausted — send best available draft rather than block the lead
        return Pair(lastSubject, lastBody)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun processNewLead(lead: Lead): String {
        repository.save(lead)
        repository.logEvent(Event.LeadReceived(leadEmail = lead.email))

        val (subject, body) = writeDraftWithReview(
            writer       = outreachWriter,
            writerPrompt = "New lead received:\n${lead.toContextString()}\n" +
                           "Write a personalised outreach email in the required SUBJECT/BODY format.",
            leadEmail    = lead.email
        )
        return sendOutreachAction.perform(
            "orchestrator",
            mapOf("leadEmail" to lead.email, "subject" to subject, "body" to body)
        )
    }

    suspend fun processReply(leadEmail: String, replyText: String): String {
        val lead = repository.getLead(leadEmail)
            ?: return "Error: Lead '$leadEmail' not found."

        when (val s = lead.status) {
            is LeadStatus.Escalated ->
                return "⛔ Lead is awaiting human review. Use: resolve $leadEmail  (reason: ${s.escalation.reason})"
            is LeadStatus.Qualified, is LeadStatus.Disqualified ->
                return "⛔ Lead is already in terminal state: ${lead.status::class.simpleName}"
            else -> {}
        }

        lead.emailThread.add(EmailMessage(EmailDirection.INBOUND, "Reply", replyText))
        repository.logEvent(Event.ReplyReceived(leadEmail = leadEmail, body = replyText))

        // ── Step 1: Escalation detection ──────────────────────────────────────
        val escalationResult = escalationDetector.processInput(
            "Lead email: $leadEmail\nIncoming message to analyse:\n\"$replyText\""
        )
        if (escalationResult.trimEnd().endsWith("ESCALATED")) {
            return escalationResult
        }

        // ── Step 2: Extract qualification data from the reply ─────────────────
        qualificationExtractor.processInput(
            "Lead email: $leadEmail\nIncoming reply to extract from:\n\"$replyText\""
        )

        // ── Step 3: Assess whether we have enough to decide ───────────────────
        val remainingFollowUps = config.maxFollowUps - lead.followUpCount
        val sufficiency = infoSufficiency.processInput(
            "Lead email: $leadEmail | Remaining follow-up budget: $remainingFollowUps\n" +
            lead.toContextString()
        ).trim()

        // ── Step 4: Route to final decision or follow-up ──────────────────────
        return when {
            sufficiency.startsWith("DECIDE_NOW") || sufficiency.startsWith("DISQUALIFY_NOW") ->
                dealDecision.processInput(
                    "Lead email: $leadEmail\n" +
                    "Sufficiency assessment: $sufficiency\n" +
                    lead.toContextString() +
                    "\nMake the final deal decision now."
                )
            else -> {
                val (subject, body) = writeDraftWithReview(
                    writer       = followUpWriter,
                    writerPrompt = "Lead email: $leadEmail | Remaining follow-ups: $remainingFollowUps\n" +
                                   lead.toContextString() +
                                   "\nWrite a follow-up email (SUBJECT/BODY format) gathering the most critical missing field.",
                    leadEmail    = leadEmail
                )
                sendFollowUpAction.perform(
                    "orchestrator",
                    mapOf("leadEmail" to leadEmail, "subject" to subject, "body" to body)
                )
            }
        }
    }

    suspend fun resolveEscalation(leadEmail: String, humanResponse: String): String {
        val lead = repository.getLead(leadEmail)
            ?: return "Error: Lead '$leadEmail' not found."
        val escalated = lead.status as? LeadStatus.Escalated
            ?: return "Lead '${lead.name}' is not escalated (status: ${lead.status::class.simpleName})."

        escalated.escalation.humanResponse = humanResponse
        lead.status = LeadStatus.AwaitingClientResponse
        repository.logEvent(Event.HumanEscalationResolved(leadEmail = leadEmail, humanResponse = humanResponse))

        val remainingFollowUps = config.maxFollowUps - lead.followUpCount
        val sufficiency = infoSufficiency.processInput(
            "Lead email: $leadEmail | Remaining follow-up budget: $remainingFollowUps\n" +
            "Escalation just resolved. Human reviewer said: \"$humanResponse\"\n" +
            lead.toContextString()
        ).trim()

        return when {
            sufficiency.startsWith("DECIDE_NOW") || sufficiency.startsWith("DISQUALIFY_NOW") ->
                dealDecision.processInput(
                    "Lead email: $leadEmail\n" +
                    "Escalation resolved. Human said: \"$humanResponse\"\n" +
                    lead.toContextString() +
                    "\nMake the final deal decision now."
                )
            else -> {
                val (subject, body) = writeDraftWithReview(
                    writer       = followUpWriter,
                    writerPrompt = "Lead email: $leadEmail | Remaining follow-ups: $remainingFollowUps\n" +
                                   "Escalation resolved. Human said: \"$humanResponse\"\n" +
                                   lead.toContextString() +
                                   "\nWrite a follow-up email (SUBJECT/BODY format) applying the human's guidance.",
                    leadEmail    = leadEmail
                )
                sendFollowUpAction.perform(
                    "orchestrator",
                    mapOf("leadEmail" to leadEmail, "subject" to subject, "body" to body)
                )
            }
        }
    }
}
