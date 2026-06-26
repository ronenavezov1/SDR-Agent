package org.example.sdr_viewmodel

import org.example.repositiories.AppRepository
import org.example.sdr.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * SdrViewModel — presentation layer.
 *
 * Single dependency: [AppRepository].
 *
 * Responsibilities:
 *  - Submit work to [AppRepository.sdrRepository] (non-blocking — repository spawns its own coroutine).
 *  - Await results via registerResult (race-condition free).
 *  - Query repositories to build display strings for [org.example.sdr_view.SdrConsoleView].
 */
class SdrViewModel(private val app: AppRepository) {
    private val repository      = app.sdrRepository
    private val emailRepository = app.sdrRepository.emailRepository
    private val formatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    // ── Commands → Repository ─────────────────────────────────────────────────

    suspend fun handleNewLead(
        name: String, email: String, company: String, message: String
    ): String {
        val lead = Lead(name = name, email = email, company = company, inboundMessage = message)
        val agentOutput = awaitResult(lead.email) { repository.submitNewLead(lead) }
        return buildString {
            appendLine(agentOutput)
            appendLine()
            append("💡 Lead email: ${lead.email}  (use this for 'reply' and 'resolve')")
        } + buildEscalationNotice(lead.email)
    }

    suspend fun handleReply(leadEmail: String, replyText: String): String {
        val agentOutput = awaitResult(leadEmail) { repository.submitReply(leadEmail, replyText) }
        return agentOutput + buildEscalationNotice(leadEmail)
    }

    suspend fun handleResolveEscalation(leadEmail: String, humanResponse: String): String {
        val lead = repository.getLead(leadEmail)
            ?: return "❌ Lead '$leadEmail' not found."
        if (lead.status !is LeadStatus.Escalated)
            return "Lead '${lead.name}' is not escalated (status: ${statusLabel(lead.status)})."
        return awaitResult(leadEmail) { repository.submitResolveEscalation(leadEmail, humanResponse) }
    }

    // ── Queries → Repository ──────────────────────────────────────────────────

    fun getLeadForEscalationPrompt(leadEmail: String): String? {
        val lead = repository.getLead(leadEmail) ?: return null
        val esc  = (lead.status as? LeadStatus.Escalated)?.escalation ?: return null
        return buildString {
            appendLine("Escalation for ${lead.name}: ${esc.reason}")
            append("Trigger: \"${esc.triggerMessage}\"")
        }
    }

    fun isLeadEscalated(leadEmail: String): Boolean =
        repository.getLead(leadEmail)?.status is LeadStatus.Escalated

    fun leadExists(leadEmail: String): Boolean =
        repository.getLead(leadEmail) != null

    fun getLeadsList(): String = buildString {
        val leads = repository.getLeads()
        if (leads.isEmpty()) { append("No leads yet. Use 'new-lead' or 'demo'."); return@buildString }
        appendLine("┌─── Leads (${leads.size}) ─────────────────────────────────────────────")
        leads.forEach { lead ->
            appendLine("│ ${statusIcon(lead.status)} ${lead.email.padEnd(30)}  " +
                "${lead.name.padEnd(20)} @ ${lead.company.padEnd(20)} → ${statusLabel(lead.status)}")
            if (lead.bookingLink != null) appendLine("│    📅 ${lead.bookingLink}")
        }
        append("└──────────────────────────────────────────────────────────────────")
    }

    fun getLeadDetail(leadEmail: String): String {
        val lead = repository.getLead(leadEmail) ?: return "Lead '$leadEmail' not found."
        return buildString {
            append(lead.toContextString())
            appendLine("Email thread (${lead.emailThread.size} messages):")
            lead.emailThread.forEachIndexed { i, msg ->
                val dir = if (msg.direction == EmailDirection.OUTBOUND) "→ SENT" else "← RECV"
                appendLine("  [${i + 1}] $dir  ${msg.subject}")
            }
        }
    }

    fun getTimeline(leadEmail: String? = null): String =
        if (leadEmail != null) formatLeadTimeline(leadEmail)
        else repository.getLeads().joinToString("\n") { formatLeadTimeline(it.email) }
            .ifEmpty { "No leads yet." }

    fun getEventLog(): String = buildString {
        val events = repository.getEvents()
        if (events.isEmpty()) { append("No events yet."); return@buildString }
        appendLine("┌─── System Event Log (${events.size} events) ──────────────────────────")
        events.forEachIndexed { i, e ->
            val time  = formatter.format(Instant.ofEpochMilli(e.timestamp))
            val icon  = iconFor(e)
            val label = e::class.simpleName?.padEnd(28) ?: ""
            appendLine("│ ${(i + 1).toString().padStart(3)}  $time  $icon  ${e.leadEmail.padEnd(28)}  $label  ${detailOf(e)}")
        }
        append("└──────────────────────────────────────────────────────────────────")
    }

    // ── Email infrastructure queries ──────────────────────────────────────────

    fun getOutbox(): String = buildString {
        val emails = emailRepository.getOutbox()
        if (emails.isEmpty()) { append("Outbox is empty."); return@buildString }
        appendLine("┌─── Outbox (${emails.size} sent) ───────────────────────────────────────")
        emails.forEachIndexed { i, e ->
            val time = formatter.format(Instant.ofEpochMilli(e.timestamp))
            appendLine("│ [${(i + 1).toString().padStart(3)}]  $time  📧  TO: ${e.to}")
            appendLine("│       SUBJECT: ${e.subject}")
            e.body.lines().take(3).forEach { appendLine("│         $it") }
            if (e.body.lines().size > 3) appendLine("│         … (${e.body.lines().size - 3} more lines)")
        }
        append("└──────────────────────────────────────────────────────────────────")
    }

    fun getInbox(): String = buildString {
        val emails = emailRepository.getInbox()
        if (emails.isEmpty()) { append("Inbox is empty."); return@buildString }
        appendLine("┌─── Inbox (${emails.size} received) ────────────────────────────────────")
        emails.forEachIndexed { i, e ->
            val time = formatter.format(Instant.ofEpochMilli(e.timestamp))
            appendLine("│ [${(i + 1).toString().padStart(3)}]  $time  📨  FROM: ${e.from}")
            appendLine("│       SUBJECT: ${e.subject}")
            e.body.lines().take(3).forEach { appendLine("│         $it") }
            if (e.body.lines().size > 3) appendLine("│         … (${e.body.lines().size - 3} more lines)")
        }
        append("└──────────────────────────────────────────────────────────────────")
    }

    fun getBookingLinks(): String = buildString {
        val agentLinks      = emailRepository.getBookingLinks()
        val clientAskLinks  = emailRepository.getClientAskBookingLinks()
        val salesTeamLinks  = emailRepository.getSalesTeamAskBookingLinks()
        val fallbackLinks   = emailRepository.getFallbackBookingLinks()
        if (agentLinks.isEmpty() && clientAskLinks.isEmpty() && salesTeamLinks.isEmpty() && fallbackLinks.isEmpty()) {
            append("No booking links yet."); return@buildString
        }
        fun section(title: String, links: Map<String, String>) {
            if (links.isEmpty()) return
            appendLine("┌─── $title (${links.size}) ${"─".repeat(maxOf(0, 54 - title.length))}")
            links.entries.forEachIndexed { i, (key, link) ->
                val label = if (key.contains("@")) key.padEnd(32) else ""
                appendLine("│ [${(i + 1).toString().padStart(3)}]  $label$link")
            }
            appendLine("└──────────────────────────────────────────────────────────────────")
        }
        section("✅ Qualified — Agent-approved",        agentLinks)
        section("🙋 ApprovedClientAsk — Lead requested human", clientAskLinks)
        section("🤝 ApprovedSalesTeamAsk — Sales team handoff", salesTeamLinks)
        section("🔁 ApprovedLlmFailed — LLM unavailable",  fallbackLinks)
    }

    fun getLeadsNeedingIntervention(): String = buildString {
        val escalated = repository.getLeads().filter { it.status is LeadStatus.Escalated }
        if (escalated.isEmpty()) { append("✅ No leads currently require human intervention."); return@buildString }
        appendLine("┌─── 🚨 LEADS REQUIRING HUMAN INTERVENTION (${escalated.size}) ─────────────")
        escalated.forEach { lead ->
            val esc  = (lead.status as LeadStatus.Escalated).escalation
            val time = formatter.format(Instant.ofEpochMilli(esc.timestamp))
            appendLine("│")
            appendLine("│  ⚠️  CONFLICT")
            appendLine("│  Lead:    ${lead.name} (${lead.email}) @ ${lead.company}")
            appendLine("│  Reason:  ${esc.reason}")
            if (esc.triggerMessage.isNotBlank()) {
                appendLine("│  Trigger: \"${esc.triggerMessage.take(100)}\"")
            }
            appendLine("│  Since:   $time")
            appendLine("│  Action:  resolve ${lead.email}")
        }
        appendLine("│")
        append("└──────────────────────────────────────────────────────────────────")
    }

    // ── Demo ──────────────────────────────────────────────────────────────────

    suspend fun runDemo(): String = buildString {
        appendLine("🎬 Running automated demo (3 scenarios)…")
        appendLine("═".repeat(65))

        appendLine(demoSection("SCENARIO 1 — Happy path: well-qualified lead"))
        val lead1 = Lead(
            name = "Sarah Chen", email = "sarah.chen@techcorp.com", company = "TechCorp Inc.",
            inboundMessage = "Hi, I saw your product at a conference. We're a 50-person engineering " +
                "team looking for better project-management tooling."
        )
        appendLine(demoStep("Processing new lead: ${lead1.name} @ ${lead1.company}"))
        appendLine(handleNewLead(lead1.name, lead1.email, lead1.company, lead1.inboundMessage))
        appendLine(demoStep("Lead replies with full qualification data"))
        appendLine(handleReply("sarah.chen@techcorp.com",
            "Thanks! We need sprint planning and bug tracking. 50 engineers, budget approved this quarter."))

        appendLine("\n═".repeat(65))
        appendLine(demoSection("SCENARIO 2 — Pricing escalation with human-in-the-loop"))
        val lead2 = Lead(
            name = "Mike Johnson", email = "mike@scaleup.io", company = "ScaleUp.io",
            inboundMessage = "We're evaluating tools for our product team. Can you tell me more?"
        )
        appendLine(demoStep("Processing new lead: ${lead2.name} @ ${lead2.company}"))
        appendLine(handleNewLead(lead2.name, lead2.email, lead2.company, lead2.inboundMessage))
        appendLine(demoStep("Lead asks about pricing (triggers escalation)"))
        appendLine(handleReply("mike@scaleup.io",
            "Our team is 25 people focused on SaaS. What's the pricing? Do you offer startup discounts?"))
        appendLine(buildEscalationNotice("mike@scaleup.io"))
        appendLine(demoStep("Human resolves escalation"))
        appendLine(handleResolveEscalation("mike@scaleup.io",
            "We offer 20% startup discount for Series A and below. Confirm use case before sharing."))

        appendLine("\n═".repeat(65))
        appendLine(demoSection("SCENARIO 3 — Disqualified: team too small"))
        val lead3 = Lead(
            name = "Alex Williams", email = "alex@freelancer.dev", company = "Freelancer.dev",
            inboundMessage = "Hi, I'm a freelance developer exploring project management tools."
        )
        appendLine(demoStep("Processing new lead: ${lead3.name} @ ${lead3.company}"))
        appendLine(handleNewLead(lead3.name, lead3.email, lead3.company, lead3.inboundMessage))
        appendLine(demoStep("Lead reveals solo freelancer"))
        appendLine(handleReply("alex@freelancer.dev",
            "I work alone managing around 5 client projects. Just exploring, no rush."))

        appendLine("\n═".repeat(65))
        appendLine("\n📊 Final Summary:")
        appendLine(getLeadsList())
        appendLine("\n📋 Event Timelines:")
        append(getTimeline())
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun awaitResult(leadEmail: String, block: () -> Unit): String {
        val deferred = repository.registerResult(leadEmail)
        block()
        return deferred.await()
    }

    private fun buildEscalationNotice(leadEmail: String): String {
        val lead = repository.getLead(leadEmail) ?: return ""
        val esc  = (lead.status as? LeadStatus.Escalated)?.escalation ?: return ""
        return buildString {
            appendLine()
            appendLine("  ┌─ 🚨 HUMAN ESCALATION REQUIRED ──────────────────────────────")
            appendLine("  │  Lead:    ${lead.name} (${lead.company})")
            appendLine("  │  Reason:  ${esc.reason}")
            appendLine("  │  Trigger: \"${esc.triggerMessage.take(120)}\"")
            appendLine("  │  Respond: resolve $leadEmail")
            append(  "  └─────────────────────────────────────────────────────────────")
        }
    }

    private fun formatLeadTimeline(leadEmail: String): String {
        val lead = repository.getLead(leadEmail) ?: return "Lead '$leadEmail' not found."
        return buildString {
            appendLine("══════════════════════════════════════════════════")
            appendLine("  Timeline — ${lead.email}  ${lead.name} @ ${lead.company}")
            appendLine("══════════════════════════════════════════════════")
            if (lead.events.isEmpty()) { appendLine("  No events."); return@buildString }
            lead.events.forEach { e ->
                val time = formatter.format(Instant.ofEpochMilli(e.timestamp))
                appendLine("  $time  ${iconFor(e)}  ${e::class.simpleName?.padEnd(30)}  ${detailOf(e)}")
            }
            append("══════════════════════════════════════════════════")
        }
    }

    private fun iconFor(event: Event) = when (event) {
        is Event.LeadReceived             -> "🆕"
        is Event.EmailSent                -> "📧"
        is Event.ReplyReceived            -> "📨"
        is Event.QualificationUpdated     -> "🔍"
        is Event.LeadQualified            -> "✅"
        is Event.LeadDisqualified         -> "❌"
        is Event.BookingLinkCreated       -> "📅"
        is Event.HumanEscalationTriggered -> "🚨"
        is Event.HumanEscalationResolved  -> "👤"
        is Event.PolicyBlocked            -> "🛡️"
        is Event.AgentDecision            -> "🤖"
        is Event.ProcessingFailed         -> "⚠️"
    }

    private fun detailOf(event: Event): String = when (event) {
        is Event.LeadReceived             -> ""
        is Event.EmailSent                -> "subject=\"${event.subject}\""
        is Event.ReplyReceived            -> "\"${event.body.take(80)}\""
        is Event.QualificationUpdated     -> "useCase=${event.useCase} teamSize=${event.teamSize} intent=${event.commercialIntent}"
        is Event.LeadQualified            -> "useCase=${event.useCase} teamSize=${event.teamSize} intent=${event.commercialIntent}"
        is Event.LeadDisqualified         -> "reason=\"${event.reason}\""
        is Event.BookingLinkCreated       -> event.bookingLink
        is Event.HumanEscalationTriggered -> "reason=\"${event.reason}\""
        is Event.HumanEscalationResolved  -> "response=\"${event.humanResponse.take(80)}\""
        is Event.PolicyBlocked            -> "[${event.policyName}] ${event.reason}"
        is Event.AgentDecision            -> "${event.decision}: ${event.reason.take(80)}"
        is Event.ProcessingFailed         -> "reason=\"${event.reason.take(80)}\""
    }

    private fun statusIcon(status: LeadStatus) = when (status) {
        is LeadStatus.New                    -> "🆕"
        is LeadStatus.AwaitingClientResponse -> "⏳"
        is LeadStatus.Escalated              -> "🚨"
        is LeadStatus.Qualified              -> "✅"
        is LeadStatus.Disqualified           -> "❌"
        is LeadStatus.ApprovedClientAsk      -> "🙋"
        is LeadStatus.ApprovedSalesTeamAsk   -> "🤝"
        is LeadStatus.ApprovedLlmFailed      -> "🔁"
    }

    private fun statusLabel(status: LeadStatus) = when (status) {
        is LeadStatus.New                    -> "New"
        is LeadStatus.AwaitingClientResponse -> "AwaitingClientResponse"
        is LeadStatus.Escalated              -> "Escalated (${status.escalation.reason.take(40)})"
        is LeadStatus.Qualified              -> "Qualified"
        is LeadStatus.Disqualified           -> "Disqualified"
        is LeadStatus.ApprovedClientAsk      -> "ApprovedClientAsk"
        is LeadStatus.ApprovedSalesTeamAsk   -> "ApprovedSalesTeamAsk"
        is LeadStatus.ApprovedLlmFailed      -> "ApprovedLlmFailed"
    }

    private fun demoSection(title: String) = "\n📋 $title\n" + "─".repeat(65)
    private fun demoStep(desc: String)     = "\n⏩ $desc"
}
