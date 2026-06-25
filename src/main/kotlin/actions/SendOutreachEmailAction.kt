package org.example.actions

import org.example.agent.Action
import org.example.mock.MockEmailService
import org.example.repositiories.SdrRepository
import org.example.sdr.*

class SendOutreachEmailAction(
    private val repository: SdrRepository,
    private val emailService: MockEmailService
) : Action {

    override val name = "sendOutreachEmail"
    override val description =
        "Sends the initial personalised outreach email to a lead. " +
        "Can only be called ONCE per lead — duplicate calls are rejected."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead to contact.",
        "subject" to "Email subject line.",
        "body"    to "Full email body text."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadEmail = args["leadEmail"]?.toString()?.trim() ?: return "Error: 'leadEmail' required."
        val subject   = args["subject"]?.toString()?.trim()   ?: return "Error: 'subject' required."
        val body      = args["body"]?.toString()?.trim()      ?: return "Error: 'body' required."

        val lead = repository.getLead(leadEmail) ?: return "Error: Lead '$leadEmail' not found."

        // ── POLICY: No duplicate outreach ─────────────────────────────────────
        if (lead.outreachSent) {
            repository.logEvent(Event.PolicyBlocked(
                leadEmail  = leadEmail,
                policyName = "NO_DUPLICATE_OUTREACH",
                reason     = "Outreach already sent to ${lead.email}"))
            return "POLICY_BLOCKED [NO_DUPLICATE_OUTREACH]: Outreach already sent to ${lead.email}."
        }

        emailService.sendEmail(lead.email, subject, body)
        lead.outreachSent = true
        lead.status = LeadStatus.AwaitingClientResponse
        lead.emailThread.add(EmailMessage(EmailDirection.OUTBOUND, subject, body))
        repository.logEvent(Event.EmailSent(leadEmail = leadEmail, subject = subject, body = body))

        return "Outreach email sent to ${lead.name} <${lead.email}>. Status → AWAITING_CLIENT_RESPONSE."
    }
}

