package org.example.sdr_orchestrator

import org.example.agent.AgentConfig
import org.example.agent.AiAgent
import org.example.llm.LlmSendException
import org.example.sdr.*
import org.example.sdr_orchestrator.sdr_actions.CreateBookingLinkAction
import org.example.sdr_orchestrator.sdr_actions.DisqualifyLeadAction
import org.example.sdr_orchestrator.sdr_actions.EscalateToHumanAction
import org.example.sdr_orchestrator.sdr_actions.SendFollowUpEmailAction
import org.example.sdr_orchestrator.sdr_actions.SendOutreachEmailAction
import org.example.sdr_orchestrator.sdr_actions.UpdateQualificationAction
import org.example.sdr_orchestrator.sdt_tools.CheckQualificationTool
import org.example.sdr_orchestrator.sdt_tools.GetLeadStateTool

/**
 * SdrOrchestrator — stateless multi-agent coordinator.
 *
 * Holds no mutable state of its own. All reads and writes flow through [OrchestratorContext],
 * which scopes the orchestrator, its actions, and its tools to a single lead.
 *
 * Pipeline (new lead):  spamDetector → qualificationExtractor → escalationDetector
 *                       → initialIntentChecker → infoSufficiency
 *                       → dealDecision  |  outreachWriter → emailReviewer → emailSanityChecker
 * Pipeline (reply):     qualificationExtractor → escalationDetector → leadReadiness
 *                       → infoSufficiency
 *                       → dealDecision  |  followUpWriter → emailReviewer → emailSanityChecker
 * resolveEscalation:    infoSufficiency → dealDecision  |  followUpWriter  |  ApprovedSalesTeamAsk
 *
 * Farewell policy: whenever a booking link is issued (any terminal state except ApprovedLlmFailed),
 * [sendFarewellEmail] sends a personalised closing email with the link. This email bypasses the
 * follow-up cap and is always sent — even a minimal fallback if the LLM is unavailable.
 */
class SdrOrchestrator(private val ctx: OrchestratorContext) {
    // ── Action instances ──────────────────────────────────────────────────────

    private val sendOutreachAction = SendOutreachEmailAction(ctx)
    private val sendFollowUpAction = SendFollowUpEmailAction(ctx)

    // ── Agent pool ────────────────────────────────────────────────────────────

    private val outreachWriter = AiAgent(AgentConfig(
        agentId      = "outreach-writer",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
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
        tools   = mapOf("getLeadState" to GetLeadStateTool(ctx)),
        actions = emptyMap(),
        maxDepth = 4, timeoutMs = 60_000L, maxHistorySize = 15
    ))

    private val followUpWriter = AiAgent(AgentConfig(
        agentId      = "followup-writer",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
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
        tools   = mapOf("getLeadState" to GetLeadStateTool(ctx)),
        actions = emptyMap(),
        maxDepth = 3, timeoutMs = 45_000L, maxHistorySize = 15
    ))

    private val emailReviewer = AiAgent(AgentConfig(
        agentId      = "email-reviewer",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
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

    private val emailSanityChecker = AiAgent(AgentConfig(
        agentId      = "email-sanity-checker",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
        systemPrompt = """
            You are a last-resort email safety gate. The email has already gone through
            a strict quality reviewer but was not fully approved. Your job is NOT to
            judge quality or style — only to decide if this email is safe to send.

            Respond with SEND if ALL of the following are true:
              • The email has a non-empty subject line.
              • The email body is coherent and readable.
              • There are no unfilled placeholders like [Your Name], [Company], [Link], etc.
              • The content would not embarrass the company if a real person received it.

            Respond with FAIL if ANY of the following are true:
              • Subject or body is blank or nearly blank.
              • The body contains bracket placeholders that were never filled in.
              • The text is gibberish, truncated mid-sentence, or clearly malformed.

            Respond with ONLY "SEND" or "FAIL". No explanation, no other text.
        """.trimIndent(),
        tools    = emptyMap(),
        actions  = emptyMap(),
        maxDepth = 2, timeoutMs = 15_000L, maxHistorySize = 5
    ))

    private val farewellWriter = AiAgent(AgentConfig(
        agentId      = "farewell-writer",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
        systemPrompt = """
            You write the final farewell email sent to a lead who has been qualified or handed
            off to the sales team. This is the last email they will receive from us.

            Call getLeadState to review the full conversation history.
            Then write a warm, concise farewell email (3–4 sentences) that:
              • Acknowledges the conversation personally — reference one specific detail the lead shared.
              • Presents the booking link naturally (e.g. "use the link below to schedule a call").
              • Expresses genuine enthusiasm about the next step.
              • Signs as "The Sales Team".

            Never reveal you are an AI. Never use placeholder text like [Your Name] or [Company].

            Your ENTIRE response must be in this exact format (nothing else):
            SUBJECT: <subject line>
            BODY:
            <full email body>
        """.trimIndent(),
        tools   = mapOf("getLeadState" to GetLeadStateTool(ctx)),
        actions = emptyMap(),
        maxDepth = 3, timeoutMs = 30_000L, maxHistorySize = 15
    ))

    private val spamDetector = AiAgent(AgentConfig(
        agentId      = "spam-detector",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
        systemPrompt = """
            You decide whether an inbound lead message is a genuine business enquiry
            or spam / garbage that should be discarded immediately.

            Respond with REAL if the message:
              • Comes from someone who appears to be a real person with a business need.
              • Contains any meaningful content, even if very short (e.g. "hi", "I need help").
              • Mentions a company, role, problem, or topic — however briefly.
              When in doubt, respond REAL. We prefer to qualify a bad lead over losing a real one.

            Respond with SPAM if the message is clearly:
              • Random characters, keyboard mashing, or gibberish (e.g. "asdfgh", "zzzzz").
              • An automated bot message or a known spam template.
              • Completely empty or whitespace-only after trimming.
              • Entirely off-topic with zero business relevance (e.g. "buy cheap meds now").

            Respond with ONLY "REAL" or "SPAM". No explanation, no other text.
        """.trimIndent(),
        tools    = emptyMap(),
        actions  = emptyMap(),
        maxDepth = 1, timeoutMs = 15_000L, maxHistorySize = 3
    ))

    private val initialIntentChecker = AiAgent(AgentConfig(
        agentId      = "initial-intent-checker",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
        systemPrompt = """
            Single purpose: classify the intent of a lead's FIRST message — before any SDR email
            has been sent. Output exactly one of four tokens.

            CLIENT_WANTS_HUMAN — respond with this if the lead clearly and explicitly:
              • Asks to speak with a human, a real person, or an account manager.
              • Says they do not want automated or bot communication.
              • Expresses hostility or frustration even in the first message.
              This takes absolute priority — check this FIRST.

            IRRELEVANT — respond with this (only if CLIENT_WANTS_HUMAN does not apply) if:
              • The message has zero connection to a B2B software or business need.
              • The lead is asking about something entirely unrelated to our domain
                (e.g. requesting information about consumer products, personal topics,
                motorcycles, cooking, travel — anything with no plausible B2B angle).
              • There is no reasonable interpretation under which this could be a sales enquiry.

            DECIDE_NOW — respond with this (only if neither above applies) if:
              • The lead has provided all three qualification fields in their first message:
                use case (clear business problem), team size (explicit number), AND commercial
                intent (active buying signal). All three must be present and unambiguous.

            PROCEED — respond with this if none of the above applies:
              • The lead gave a normal introduction, a partial description, or just said "hi".
              • We should send a personalised outreach email and continue the qualification process.

            Respond with ONLY one token: CLIENT_WANTS_HUMAN, IRRELEVANT, DECIDE_NOW, or PROCEED.
            No explanation, no other text.
        """.trimIndent(),
        tools    = emptyMap(),
        actions  = emptyMap(),
        maxDepth = 1, timeoutMs = 15_000L, maxHistorySize = 5
    ))

    private val escalationDetector = AiAgent(AgentConfig(
        agentId      = "escalation-detector",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
        systemPrompt = """
            You analyse incoming lead messages for escalation triggers.

            STEP 1 — ALWAYS call getLeadState first to read the full event history and the last
            outgoing email. You MUST understand what question the SDR asked before judging the reply.

            STEP 2 — Escalation triggers are ONLY:
              • The lead INITIATES discussion of pricing, discounts, costs, or budget negotiation.
              • The lead raises legal/contract terms or asks for a formal agreement.
              • The lead makes competitor comparisons that require commercial knowledge to answer.

            CRITICAL — these are NOT escalation triggers:
              • Any answer that is a direct response to a question the SDR already asked.
                Example: SDR asked "how many people are on your team?" → lead answers "1000" → SAFE.
              • Numbers, dates, or quantities given as qualification data (team size, usage volume, etc.).
              • General enthusiasm, complaints about workload, or vague budget mentions without specifics.
              • Statements that budget EXISTS or has been approved — this is a positive buying signal, not a negotiation.
                Example: "budget approved", "we have budget for this", "budget confirmed" → SAFE.
              • Asking about next steps, timelines, or how to proceed — these are buying signals, not escalations.

            If a genuine trigger is detected:
              1. Call escalateToHuman with a specific reason and the exact triggering quote.
              2. End your response with exactly: ESCALATED

            If no trigger is detected:
              Respond with exactly: SAFE

            Do not add any other text after ESCALATED or SAFE.
        """.trimIndent(),
        tools   = mapOf("getLeadState" to GetLeadStateTool(ctx)),
        actions = mapOf("escalateToHuman" to EscalateToHumanAction(ctx)),
        maxDepth = 3, timeoutMs = 30_000L, maxHistorySize = 15
    ))

    private val qualificationExtractor = AiAgent(AgentConfig(
        agentId      = "qualification-extractor",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
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
        tools   = mapOf("getLeadState" to GetLeadStateTool(ctx)),
        actions = mapOf("updateQualification" to UpdateQualificationAction(ctx)),
        maxDepth = 3, timeoutMs = 30_000L, maxHistorySize = 10
    ))

    private val leadReadiness = AiAgent(AgentConfig(
        agentId      = "lead-readiness",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
        systemPrompt = """
            Single purpose: decide how to proceed after a lead's reply — three possible outcomes.

            STEP 1 — Call getLeadState and read the FULL event history and entire email thread.
            You MUST analyse the lead's behaviour across ALL previous replies, not just the latest one.

            STEP 2 — Choose exactly one token:

            CLIENT_WANTS_HUMAN — respond with this if ANY of these are true:
              • The lead explicitly asks for a human, a real person, a real agent, or human contact.
              • The lead says they don't want automated or bot replies.
              • The lead expresses frustration, anger, or hostility in ANY message
                (e.g. "stop bothering me", "leave me alone", "enough", ALL CAPS frustration, "!!!").
              This takes absolute priority — check this BEFORE anything else.

            DECIDE_NOW — respond with this if CLIENT_WANTS_HUMAN does not apply AND any of these are true:
              • The lead has sent 3 or more replies without providing meaningful qualification data.
              • Remaining follow-up budget is 1 or less.
              • Enough data exists (team_size AND at least one of use_case/commercial_intent known)
                and the latest message adds nothing new.
              • The lead gives consistently short, non-committal answers across multiple replies.

            GATHER_MORE — respond with this ONLY if ALL are true:
              • CLIENT_WANTS_HUMAN does not apply.
              • DECIDE_NOW does not apply.
              • The lead is engaged and cooperative.
              • Critical qualification fields are still missing and the lead seems willing to share.
              • 2 or more follow-up messages remain in the budget.

            Respond with ONLY ONE token — no explanation, no other text:
              CLIENT_WANTS_HUMAN
              DECIDE_NOW
              GATHER_MORE
        """.trimIndent(),
        tools    = mapOf("getLeadState" to GetLeadStateTool(ctx)),
        actions  = emptyMap(),
        maxDepth = 3, timeoutMs = 20_000L, maxHistorySize = 20
    ))

    private val infoSufficiency = AiAgent(AgentConfig(
        agentId      = "info-sufficiency",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
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
            "getLeadState" to GetLeadStateTool(ctx),
            "checkQualification" to CheckQualificationTool(ctx)
        ),
        actions  = emptyMap(),
        maxDepth = 4, timeoutMs = 30_000L, maxHistorySize = 15
    ))

    private val dealDecision = AiAgent(AgentConfig(
        agentId      = "deal-decision",
        workOnId     = ctx.leadEmail,
        llmClient    = ctx.llmClient,
        systemPrompt = """
            You make the final deal decision for a lead and act on it immediately.
            Steps: call getLeadState then checkQualification to assess the current data.

            IRON RULE — QUALIFIED status requires ALL THREE fields to be known:
              use_case, team_size, AND commercial_intent.
              If ANY field is missing, you CANNOT qualify — you must disqualify instead.

            If QUALIFIED (all three fields present and criteria met):
              1. Call createBookingLink.
              2. Write a warm, professional closing message (plain text, not a tool call)
                 that includes the booking link and next steps.

            If DISQUALIFIED (criteria not met OR any qualification field is missing):
              1. Call disqualifyLead with a specific, factual reason.
              2. Write a brief, polite farewell message.

            Always reach a conclusion — never leave a lead without a final action.
        """.trimIndent(),
        tools = mapOf(
            "getLeadState" to GetLeadStateTool(ctx),
            "checkQualification" to CheckQualificationTool(ctx)
        ),
        actions = mapOf(
            "createBookingLink" to CreateBookingLinkAction(ctx),
            "disqualifyLead" to DisqualifyLeadAction(ctx)
        ),
        maxDepth = 5, timeoutMs = 60_000L, maxHistorySize = 20
    ))

    // ── Draft helpers ─────────────────────────────────────────────────────────

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

    private suspend fun writeDraftWithReview(
        writer: AiAgent,
        writerPrompt: String,
        leadEmail: String
    ): Pair<String, String> {
        var feedback = ""
        // Best parseable draft seen so far — updated on every successful parse.
        // Kept separate from lastSubject/lastBody so we always have the most
        // recently iterated candidate available for the sanity-checker fallback.
        var bestSubject: String? = null
        var bestBody: String? = null

        repeat(ctx.config.maxEmailDraftRetries) { attempt ->
            val prompt = if (feedback.isEmpty()) writerPrompt
            else "$writerPrompt\n\n⚠️ REVIEWER FEEDBACK (attempt ${attempt + 1}/${ctx.config.maxEmailDraftRetries}): $feedback\n" +
                "Fix ALL mentioned issues before outputting the revised draft."

            val draftText = writer.processInput(prompt)
            val parsed = parseDraft(draftText)

            if (parsed == null) {
                feedback = "Output was not in required format. Use exactly:\nSUBJECT: <line>\nBODY:\n<text>"
                return@repeat
            }

            val (subject, body) = parsed
            bestSubject = subject
            bestBody = body

            val reviewInput = "Review this email draft for lead <$leadEmail>:\n\nSUBJECT: $subject\nBODY:\n$body"
            val reviewResult = emailReviewer.processInput(reviewInput).trim()

            if (reviewResult.startsWith("APPROVED")) {
                return Pair(subject, body)
            }
            feedback = reviewResult.removePrefix("REJECTED:").trim()
        }

        // ── Sanity-checker fallback ──────────────────────────────────────────
        // All strict-review retries exhausted. If we have a parseable draft,
        // let the lenient sanity checker decide whether it is safe to send.
        // If we never got a parseable draft at all, fail immediately.
        val subject = bestSubject
        val body    = bestBody

        if (subject == null || body == null) {
            throw LlmSendException(
                agentId       = "email-writer",
                providerName  = ctx.llmClient::class.simpleName ?: "LLM",
                modelName     = "unknown",
                errorMessage  = "Writer never produced a parseable SUBJECT/BODY draft after " +
                    "${ctx.config.maxEmailDraftRetries} attempts for lead <$leadEmail>. " +
                    "LLM responses were likely malformed (rate-limiting or API errors)."
            )
        }

        val sanityInput = "Lead: <$leadEmail>\n\nSUBJECT: $subject\nBODY:\n$body"
        val sanityResult = emailSanityChecker.processInput(sanityInput).trim()

        if (sanityResult.startsWith("SEND")) {
            return Pair(subject, body)
        }

        throw LlmSendException(
            agentId       = "email-sanity-checker",
            providerName  = ctx.llmClient::class.simpleName ?: "LLM",
            modelName     = "unknown",
            errorMessage  = "Draft for lead <$leadEmail> failed strict review after " +
                "${ctx.config.maxEmailDraftRetries} attempts and was rejected by the sanity checker. " +
                "Reason: $sanityResult"
        )
    }

    /**
     * Sends a personalised farewell email with [bookingLink] to the lead.
     *
     * This email bypasses the follow-up cap — it is always sent once a booking link is issued.
     * If the LLM fails, a minimal template fallback is used so the lead always receives the link.
     * Does NOT increment [Lead.followUpCount].
     */
    private suspend fun sendFarewellEmail(bookingLink: String) {
        val lead = ctx.getLead() ?: return

        val (subject, body) = try {
            val draftText = farewellWriter.processInput(
                "Lead email: ${ctx.leadEmail}\n" +
                "Booking link to include: $bookingLink\n\n" +
                lead.toContextString() +
                "\nWrite the farewell email with the booking link."
            )
            parseDraft(draftText) ?: Pair(
                "Your next steps with us",
                "Thank you for your time. Here is your booking link to connect with our team:\n$bookingLink\n\nThe Sales Team"
            )
        } catch (e: LlmSendException) {
            Pair(
                "Your next steps with us",
                "Thank you for your time. Here is your booking link to connect with our team:\n$bookingLink\n\nThe Sales Team"
            )
        }

        val currentLead = ctx.getLead() ?: return
        ctx.sendEmail(currentLead.email, subject, body)
        currentLead.emailThread.add(EmailMessage(EmailDirection.OUTBOUND, subject, body))
        ctx.saveLead(currentLead)
        ctx.logEvent(Event.EmailSent(leadEmail = ctx.leadEmail, subject = subject, body = body))
    }

    /**
     * Runs [dealDecision] and, if it qualifies the lead, sends a farewell email with the booking link.
     */
    private suspend fun decideAndFarewell(prompt: String): String {
        val result = dealDecision.processInput(prompt)
        val updatedLead = ctx.getLead()
        if (updatedLead?.status == LeadStatus.Qualified) {
            updatedLead.bookingLink?.let { sendFarewellEmail(it) }
        }
        return result
    }

    // ── Public API — all methods return Unit, results flow to repository ───────

    /**
     * Unified entry point for both new leads and replies.
     *
     * @param newLead  Non-null for the very first message from a lead (not yet saved).
     *                 Null for subsequent replies (lead is loaded from context).
     * @param messageText  The raw text of the incoming message (initial or reply).
     */
    suspend fun process(newLead: Lead? = null, messageText: String) {
        val scopedLeadEmail = ctx.leadEmail
        val isFirstMessage = newLead != null

        val lead: Lead
        if (isFirstMessage) {
            // Lead is always saved (+ LeadReceived logged) by submitNewLead before
            // tryWithAllClients is called — load it from the repo.
            lead = ctx.getLead() ?: run {
                ctx.emitResult("Error: Lead '$scopedLeadEmail' not found.")
                return
            }

            // Step 0 (new leads only): Spam gate — disqualify junk before touching anything else
            val spamResult = spamDetector.processInput(
                "Lead email: $scopedLeadEmail\n" +
                "Initial message:\n\"$messageText\""
            ).trim()
            if (spamResult.startsWith("SPAM")) {
                val disqualifyResult = DisqualifyLeadAction(ctx).perform(
                    "sdr_orchestrator",
                    mapOf("leadEmail" to scopedLeadEmail, "reason" to "Spam or non-genuine enquiry detected on initial message.")
                )
                ctx.emitResult("🗑️ $disqualifyResult")
                return
            }
        } else {
            val loaded = ctx.getLead()
            if (loaded == null) {
                ctx.emitResult("Error: Lead '$scopedLeadEmail' not found.")
                return
            }
            // Terminal-state guard is enforced upstream in tryWithAllClients via LeadStatus.isTerminal.
            // The Escalated check stays here because Escalated is not terminal — it needs a special message.
            if (loaded.status is LeadStatus.Escalated) {
                val s = loaded.status as LeadStatus.Escalated
                ctx.emitResult("⛔ Lead is awaiting human review. Use: resolve $scopedLeadEmail  (reason: ${s.escalation.reason})")
                return
            }
            loaded.emailThread.add(EmailMessage(EmailDirection.INBOUND, "Reply", messageText))
            ctx.saveLead(loaded)
            ctx.logEvent(Event.ReplyReceived(leadEmail = scopedLeadEmail, body = messageText))
            lead = loaded
        }

        // Full context snapshot passed to every decision agent
        val leadContext = ctx.getLead()?.toContextString() ?: lead.toContextString()

        // Step 1: Extract qualification data from the message FIRST — so fields are
        // populated even if the pipeline stops early (e.g. escalation, CLIENT_WANTS_HUMAN).
        qualificationExtractor.processInput(
            "Lead email: $scopedLeadEmail\n" +
            "Incoming message to extract from:\n\"$messageText\"\n\n" +
            leadContext
        )

        // Refresh context after extraction so subsequent agents see the updated fields
        val extractedLead = ctx.getLead() ?: run {
            ctx.emitResult("Error: Lead '$scopedLeadEmail' not found.")
            return
        }
        val refreshedContext = extractedLead.toContextString()

        // Step 2: Escalation check
        val escalationResult = escalationDetector.processInput(
            "Lead email: $scopedLeadEmail\n" +
            "Incoming message to analyse:\n\"$messageText\"\n\n" +
            refreshedContext
        )
        if (escalationResult.trimEnd().endsWith("ESCALATED")) {
            ctx.emitResult(escalationResult)
            return
        }

        // Step 3: Intent / readiness check
        val remainingFollowUps = ctx.config.maxFollowUps - extractedLead.followUpCount
        if (isFirstMessage) {
            // New lead — check initial intent: human request, immediate decision, or normal proceed
            val intent = initialIntentChecker.processInput(
                "Lead email: $scopedLeadEmail\n" +
                "Initial message:\n\"$messageText\"\n\n" +
                refreshedContext
            ).trim()

            if (intent.startsWith("CLIENT_WANTS_HUMAN")) {
                val currentLead = ctx.getLead() ?: run {
                    ctx.emitResult("Error: Lead '$scopedLeadEmail' not found.")
                    return
                }
                val link = ctx.createClientAskBookingLink()
                currentLead.status = LeadStatus.ApprovedClientAsk(link)
                currentLead.bookingLink = link
                ctx.saveLead(currentLead)
                ctx.logEvent(Event.BookingLinkCreated(leadEmail = scopedLeadEmail, bookingLink = link, reason = BookingLinkReason.ClientRequestedHuman))
                sendFarewellEmail(link)
                ctx.emitResult(
                    "🙋 Lead ${currentLead.name} requested human contact on first message. " +
                    "Status → ApprovedClientAsk. Booking link: $link"
                )
                return
            }

            if (intent.startsWith("IRRELEVANT")) {
                val result = DisqualifyLeadAction(ctx).perform(
                    "sdr_orchestrator",
                    mapOf(
                        "leadEmail" to scopedLeadEmail,
                        "reason" to "Initial message is unrelated to our business domain."
                    )
                )
                ctx.emitResult("🚫 $result")
                return
            }

            if (intent.startsWith("DECIDE_NOW")) {
                val currentLead = ctx.getLead() ?: run {
                    ctx.emitResult("Error: Lead '$scopedLeadEmail' not found.")
                    return
                }
                val result = decideAndFarewell(
                    "Lead email: $scopedLeadEmail\n" +
                        "Reason: lead provided all qualification data in their first message.\n" +
                        currentLead.toContextString() +
                        "\nMake the final deal decision now."
                )
                ctx.emitResult(result)
                return
            }
            // PROCEED — fall through to infoSufficiency + outreachWriter
        } else {
            // Reply — check behaviour patterns across the full conversation history
            val readiness = leadReadiness.processInput(
                "Lead email: $scopedLeadEmail\n" +
                "Remaining follow-up budget: $remainingFollowUps / ${ctx.config.maxFollowUps}\n" +
                "Latest message from lead:\n\"$messageText\"\n\n" +
                refreshedContext
            ).trim()

            if (readiness.startsWith("CLIENT_WANTS_HUMAN")) {
                val currentLead = ctx.getLead() ?: run {
                    ctx.emitResult("Error: Lead '$scopedLeadEmail' not found.")
                    return
                }
                val link = ctx.createClientAskBookingLink()
                currentLead.status = LeadStatus.ApprovedClientAsk(link)
                currentLead.bookingLink = link
                ctx.saveLead(currentLead)
                ctx.logEvent(Event.BookingLinkCreated(leadEmail = scopedLeadEmail, bookingLink = link, reason = BookingLinkReason.ClientRequestedHuman))
                sendFarewellEmail(link)
                ctx.emitResult(
                    "🙋 Lead ${currentLead.name} requested human contact. " +
                    "Status → ApprovedClientAsk. Booking link: $link"
                )
                return
            }

            if (readiness.startsWith("DECIDE_NOW")) {
                val currentLead = ctx.getLead() ?: run {
                    ctx.emitResult("Error: Lead '$scopedLeadEmail' not found.")
                    return
                }
                val result = decideAndFarewell(
                    "Lead email: $scopedLeadEmail\n" +
                        "Reason for immediate decision: lead readiness check returned DECIDE_NOW.\n" +
                        currentLead.toContextString() +
                        "\nMake the final deal decision now with the data available."
                )
                ctx.emitResult(result)
                return
            }
        }

        // Step 4: Sufficiency check (qualification already extracted in Step 1)
        val sufficiency = infoSufficiency.processInput(
            "Lead email: $scopedLeadEmail | Remaining follow-up budget: $remainingFollowUps\n" +
                extractedLead.toContextString()
        ).trim()

        val result = when {
            sufficiency.startsWith("DECIDE_NOW") || sufficiency.startsWith("DISQUALIFY_NOW") ->
                decideAndFarewell(
                    "Lead email: $scopedLeadEmail\n" +
                        "Sufficiency assessment: $sufficiency\n" +
                        extractedLead.toContextString() +
                        "\nMake the final deal decision now."
                )
            else -> {
                val writer = if (isFirstMessage) outreachWriter else followUpWriter
                val writerPrompt = if (isFirstMessage)
                    "New lead received:\n${extractedLead.toContextString()}\n" +
                        "Write a personalised outreach email in the required SUBJECT/BODY format."
                else
                    "Lead email: $scopedLeadEmail | Remaining follow-ups: $remainingFollowUps\n" +
                        extractedLead.toContextString() +
                        "\nWrite a follow-up email (SUBJECT/BODY format) gathering the most critical missing field."
                val (subject, body) = writeDraftWithReview(writer, writerPrompt, scopedLeadEmail)
                if (isFirstMessage)
                    sendOutreachAction.perform(
                        "sdr_orchestrator",
                        mapOf("leadEmail" to scopedLeadEmail, "subject" to subject, "body" to body)
                    )
                else
                    sendFollowUpAction.perform(
                        "sdr_orchestrator",
                        mapOf("leadEmail" to scopedLeadEmail, "subject" to subject, "body" to body)
                    )
            }
        }
        ctx.emitResult(result)
    }

    suspend fun resolveEscalation(humanResponse: String) {
        val scopedLeadEmail = ctx.leadEmail
        val lead = ctx.getLead()
        if (lead == null) {
            ctx.emitResult("Error: Lead '$scopedLeadEmail' not found.")
            return
        }
        val escalated = lead.status as? LeadStatus.Escalated
        if (escalated == null) {
            ctx.emitResult("Lead '${lead.name}' is not escalated (status: ${lead.status::class.simpleName}).")
            return
        }

        escalated.escalation.humanResponse = humanResponse
        lead.status = LeadStatus.AwaitingClientResponse
        ctx.saveLead(lead)
        ctx.logEvent(Event.HumanEscalationResolved(leadEmail = scopedLeadEmail, humanResponse = humanResponse))

        val remainingFollowUps = ctx.config.maxFollowUps - lead.followUpCount

        // Ask infoSufficiency to interpret the human's intent AND the current data state.
        // If the human explicitly closes/forwards the lead → DECIDE_NOW.
        // If the human just answered a question (e.g. "10% discount approved") → NEEDS_MORE_INFO.
        val sufficiency = infoSufficiency.processInput(
            "Lead email: $scopedLeadEmail | Remaining follow-up budget: $remainingFollowUps\n" +
            "A human sales representative just resolved an escalation.\n" +
            "Human said: \"$humanResponse\"\n" +
            "IMPORTANT: If the human's response explicitly closes the lead, forwards it, or gives a " +
            "clear approve/reject signal — respond DECIDE_NOW. " +
            "If they merely answered a specific question (pricing, discount, policy) to continue the " +
            "conversation — respond NEEDS_MORE_INFO so we can relay the answer to the lead.\n" +
            lead.toContextString()
        ).trim()

        val result = when {
            sufficiency.startsWith("DECIDE_NOW") || sufficiency.startsWith("DISQUALIFY_NOW") -> {
                val hasAllFields = lead.useCase != null && lead.teamSize != null && lead.commercialIntent != null
                if (hasAllFields) {
                    decideAndFarewell(
                        "Lead email: $scopedLeadEmail\n" +
                            "A human sales representative resolved an escalation.\n" +
                            "Human said: \"$humanResponse\"\n" +
                            "The human's response is authoritative — honour it in your decision.\n" +
                            lead.toContextString() +
                            "\nMake the final deal decision now, guided by the human's response."
                    )
                } else {
                    // Human explicitly closing but data is incomplete → sales-team handoff
                    val link = ctx.createSalesTeamAskBookingLink()
                    lead.status = LeadStatus.ApprovedSalesTeamAsk(link)
                    lead.bookingLink = link
                    ctx.saveLead(lead)
                    ctx.logEvent(Event.BookingLinkCreated(leadEmail = scopedLeadEmail, bookingLink = link, reason = BookingLinkReason.SalesTeamHandoff))
                    sendFarewellEmail(link)
                    "🤝 Lead ${lead.name} passed forward by sales team (data incomplete). " +
                    "Status → ApprovedSalesTeamAsk. Booking link: $link"
                }
            }
            else -> {
                // Human answered a specific question → relay the answer to the lead
                // and gather the next missing qualification field
                val (subject, body) = writeDraftWithReview(
                    writer = followUpWriter,
                    writerPrompt = "Lead email: $scopedLeadEmail | Remaining follow-ups: $remainingFollowUps\n" +
                        lead.toContextString() +
                        "\nA human sales representative answered the escalated question: \"$humanResponse\"\n" +
                        "Your email MUST first relay this answer clearly to the lead " +
                        "(e.g. confirm the discount, address their concern), " +
                        "then ask ONE focused question about the most critical missing qualification field.",
                    leadEmail = scopedLeadEmail
                )
                sendFollowUpAction.perform(
                    "sdr_orchestrator",
                    mapOf("leadEmail" to scopedLeadEmail, "subject" to subject, "body" to body)
                )
            }
        }
        ctx.emitResult(result)
    }
}
