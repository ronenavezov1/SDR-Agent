package org.example.actions

import org.example.agent.Action
import org.example.mock.MockEmailService
import org.example.repositiories.SdrRepository
import org.example.sdr.*

class SendFollowUpEmailAction(
    private val repository: SdrRepository,
    private val emailService: MockEmailService,
    private val config: QualificationConfig
) : Action {

    override val name = "sendFollowUpEmail"
    override val description =
        "Sends a follow-up email to gather missing qualification info. " +
        "Cannot exceed the maximum follow-up limit (${config.maxFollowUps})."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead to follow up with.",
        "subject"   to "Email subject line.",
        "body"      to "Full email body text asking for the missing information."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadEmail = args["leadEmail"]?.toString()?.trim() ?: return "Error: 'leadEmail' required."
        val subject   = args["subject"]?.toString()?.trim()   ?: return "Error: 'subject' required."
        val body      = args["body"]?.toString()?.trim()      ?: return "Error: 'body' required."

        val lead = repository.getLead(leadEmail) ?: return "Error: Lead '$leadEmail' not found."

        // ── POLICY: No email while escalated ──────────────────────────────────
        if (lead.status is LeadStatus.Escalated) {
            return "POLICY_BLOCKED [NO_EMAIL_ESCALATED]: Lead is awaiting human review. Resolve escalation first."
        }

        // ── POLICY: Max follow-ups ─────────────────────────────────────────────
        if (lead.followUpCount >= config.maxFollowUps) {
            repository.logEvent(Event.PolicyBlocked(
                leadEmail  = leadEmail,
                policyName = "MAX_FOLLOW_UPS",
                reason     = "Reached ${config.maxFollowUps} follow-ups for ${lead.name}"))
            return "POLICY_BLOCKED [MAX_FOLLOW_UPS]: Maximum ${config.maxFollowUps} follow-ups reached. Disqualify or escalate."
        }

        emailService.sendEmail(lead.email, subject, body)
        lead.followUpCount++
        lead.status = LeadStatus.AwaitingClientResponse
        lead.emailThread.add(EmailMessage(EmailDirection.OUTBOUND, subject, body))
        repository.logEvent(Event.EmailSent(leadEmail = leadEmail, subject = subject, body = body))

        return "Follow-up #${lead.followUpCount} sent to ${lead.name}. " +
               "${config.maxFollowUps - lead.followUpCount} follow-ups remaining."
    }
}

