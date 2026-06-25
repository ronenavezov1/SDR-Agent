package org.example.actions

import org.example.agent.Action
import org.example.mock.MockEmailService
import org.example.sdr.*

/**
 * Action: sends the first (and only) outreach email to a lead.
 *
 * POLICY enforced: NO_DUPLICATE_OUTREACH — if outreachSent is already true,
 * the tool refuses and returns a POLICY_BLOCKED message. The LLM cannot
 * bypass this — the check is in code, not in the prompt.
 */
class SendOutreachEmailAction(
    private val store: LeadStore,
    private val emailService: MockEmailService,
    private val auditLog: AuditLog
) : Action {

    override val name = "sendOutreachEmail"
    override val description =
        "Sends the initial personalised outreach email to a lead. " +
        "Can only be called ONCE per lead — duplicate calls are rejected."
    override val parameters = mapOf(
        "leadId"  to "The ID of the lead to contact.",
        "subject" to "Email subject line.",
        "body"    to "Full email body text."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadId  = args["leadId"]?.toString()?.trim()  ?: return "Error: 'leadId' required."
        val subject = args["subject"]?.toString()?.trim() ?: return "Error: 'subject' required."
        val body    = args["body"]?.toString()?.trim()    ?: return "Error: 'body' required."

        val lead = store.get(leadId) ?: return "Error: Lead '$leadId' not found."

        // ── POLICY: No duplicate outreach ─────────────────────────────────────
        if (lead.outreachSent) {
            val msg = "POLICY_BLOCKED [NO_DUPLICATE_OUTREACH]: Outreach already sent to ${lead.email}. Cannot send again."
            auditLog.log(AuditEvent(AuditEventType.POLICY_BLOCKED, leadId,
                mapOf("policy" to "NO_DUPLICATE_OUTREACH")))
            return msg
        }

        emailService.sendEmail(lead.email, subject, body)
        lead.outreachSent = true
        lead.status = LeadStatus.AWAITING_REPLY
        lead.emailThread.add(EmailMessage(EmailDirection.OUTBOUND, subject, body))

        auditLog.log(AuditEvent(AuditEventType.EMAIL_SENT, leadId,
            mapOf("type" to "OUTREACH", "to" to lead.email, "subject" to subject)))

        return "Outreach email sent to ${lead.name} <${lead.email}>. Lead status → AWAITING_REPLY."
    }
}
