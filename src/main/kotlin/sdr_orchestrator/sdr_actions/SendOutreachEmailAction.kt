package org.example.sdr_orchestrator.sdr_actions

import org.example.agent.Action
import org.example.sdr.*
import org.example.sdr_orchestrator.OrchestratorContext

class SendOutreachEmailAction(private val ctx: OrchestratorContext) : Action {

    override val name = "sendOutreachEmail"
    override val description =
        "Sends the initial personalised outreach email to a lead. " +
        "Can only be called ONCE per lead — duplicate calls are rejected."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead to contact.",
        "subject" to "Email subject line.",
        "body" to "Full email body text."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val subject = args["subject"]?.toString()?.trim() ?: return "Error: 'subject' required."
        val body = args["body"]?.toString()?.trim() ?: return "Error: 'body' required."

        val lead = ctx.getLead() ?: return "Error: Lead not found."

        if (lead.outreachSent) {
            ctx.logEvent(Event.PolicyBlocked(
                leadEmail = ctx.leadEmail,
                policyName = "NO_DUPLICATE_OUTREACH",
                reason = "Outreach already sent to ${lead.email}"
            ))
            return "POLICY_BLOCKED [NO_DUPLICATE_OUTREACH]: Outreach already sent to ${lead.email}."
        }

        ctx.sendEmail(lead.email, subject, body)
        lead.outreachSent = true
        lead.status = LeadStatus.AwaitingClientResponse
        lead.emailThread.add(EmailMessage(EmailDirection.OUTBOUND, subject, body))
        ctx.saveLead(lead)
        ctx.logEvent(Event.EmailSent(leadEmail = ctx.leadEmail, subject = subject, body = body))

        return "Outreach email sent to ${lead.name} <${lead.email}>. Status → AWAITING_CLIENT_RESPONSE."
    }
}
