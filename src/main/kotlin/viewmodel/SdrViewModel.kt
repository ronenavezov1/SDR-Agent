package org.example.viewmodel

import org.example.orchestrator.SdrOrchestrator
import org.example.repositiories.SdrRepository
import org.example.sdr.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * SdrViewModel — presentation layer.
 *
 * Dependency direction (strict, no cycles):
 *   SdrViewModel → SdrOrchestrator  (for state-mutating commands)
 *   SdrViewModel → SdrRepository    (for read-only queries)
 *
 * Responsibilities:
 *  - Route user commands to [SdrOrchestrator].
 *  - Query [SdrRepository] to build display strings for [org.example.view.SdrConsoleView].
 *  - The View calls a method here and prints the returned String — no logic in the View.
 */
class SdrViewModel(
    private val orchestrator: SdrOrchestrator,
    private val repository:   SdrRepository
) {
    private val formatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    // ── Commands → Orchestrator ───────────────────────────────────────────────

    suspend fun handleNewLead(
        name: String, email: String, company: String, message: String
    ): String {
        val lead = Lead(name = name, email = email, company = company, inboundMessage = message)
        val agentOutput = orchestrator.processNewLead(lead)
        return buildString {
            appendLine(agentOutput)
            appendLine()
            append("💡 Lead email: ${lead.email}  (use this for 'reply' and 'resolve')")
        } + buildEscalationNotice(lead.email)
    }

    suspend fun handleReply(leadEmail: String, replyText: String): String {
        val agentOutput = orchestrator.processReply(leadEmail, replyText)
        return agentOutput + buildEscalationNotice(leadEmail)
    }

    suspend fun handleResolveEscalation(leadEmail: String, humanResponse: String): String {
        val lead = repository.getLead(leadEmail)
            ?: return "❌ Lead '$leadEmail' not found."
        if (lead.status !is LeadStatus.Escalated)
            return "Lead '${lead.name}' is not escalated (status: ${statusLabel(lead.status)})."
        return orchestrator.resolveEscalation(leadEmail, humanResponse)
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
        appendLine(orchestrator.processNewLead(lead1))
        appendLine(demoStep("Lead replies with full qualification data"))
        appendLine(orchestrator.processReply("sarah.chen@techcorp.com",
            "Thanks! We need sprint planning and bug tracking. 50 engineers, budget approved this quarter."))

        appendLine("\n═".repeat(65))
        appendLine(demoSection("SCENARIO 2 — Pricing escalation with human-in-the-loop"))
        val lead2 = Lead(
            name = "Mike Johnson", email = "mike@scaleup.io", company = "ScaleUp.io",
            inboundMessage = "We're evaluating tools for our product team. Can you tell me more?"
        )
        appendLine(demoStep("Processing new lead: ${lead2.name} @ ${lead2.company}"))
        appendLine(orchestrator.processNewLead(lead2))
        appendLine(demoStep("Lead asks about pricing (triggers escalation)"))
        appendLine(orchestrator.processReply("mike@scaleup.io",
            "Our team is 25 people focused on SaaS. What's the pricing? Do you offer startup discounts?"))
        appendLine(buildEscalationNotice("mike@scaleup.io"))
        appendLine(demoStep("Human resolves escalation"))
        appendLine(orchestrator.resolveEscalation("mike@scaleup.io",
            "We offer 20% startup discount for Series A and below. Confirm use case before sharing."))

        appendLine("\n═".repeat(65))
        appendLine(demoSection("SCENARIO 3 — Disqualified: team too small"))
        val lead3 = Lead(
            name = "Alex Williams", email = "alex@freelancer.dev", company = "Freelancer.dev",
            inboundMessage = "Hi, I'm a freelance developer exploring project management tools."
        )
        appendLine(demoStep("Processing new lead: ${lead3.name} @ ${lead3.company}"))
        appendLine(orchestrator.processNewLead(lead3))
        appendLine(demoStep("Lead reveals solo freelancer"))
        appendLine(orchestrator.processReply("alex@freelancer.dev",
            "I work alone managing around 5 client projects. Just exploring, no rush."))

        appendLine("\n═".repeat(65))
        appendLine("\n📊 Final Summary:")
        appendLine(getLeadsList())
        appendLine("\n📋 Event Timelines:")
        append(getTimeline())
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
    }

    private fun statusIcon(status: LeadStatus) = when (status) {
        is LeadStatus.New                    -> "🆕"
        is LeadStatus.AwaitingClientResponse -> "⏳"
        is LeadStatus.Escalated              -> "🚨"
        is LeadStatus.Qualified              -> "✅"
        is LeadStatus.Disqualified           -> "❌"
    }

    private fun statusLabel(status: LeadStatus) = when (status) {
        is LeadStatus.New                    -> "New"
        is LeadStatus.AwaitingClientResponse -> "AwaitingClientResponse"
        is LeadStatus.Escalated              -> "Escalated (${status.escalation.reason.take(40)})"
        is LeadStatus.Qualified              -> "Qualified"
        is LeadStatus.Disqualified           -> "Disqualified"
    }

    private fun demoSection(title: String) = "\n📋 $title\n" + "─".repeat(65)
    private fun demoStep(desc: String)     = "\n⏩ $desc"
}
