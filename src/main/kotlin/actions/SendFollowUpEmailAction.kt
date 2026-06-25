package org.example.actions

import org.example.agent.Action
import org.example.mock.MockEmailService
import org.example.sdr.*

/**
 * Action: sends a follow-up email to collect missing qualification information.
 *
 * POLICIES enforced:
 *  1. MAX_FOLLOW_UPS     — refuses if followUpCount >= config.maxFollowUps
 *  2. NO_EMAIL_ESCALATED — refuses if lead is in ESCALATED_HUMAN state
 */
class SendFollowUpEmailAction(
    private val store: LeadStore,
    private val emailService: MockEmailService,
    private val auditLog: AuditLog,
    private val config: QualificationConfig
) : Action {

    override val name = "sendFollowUpEmail"
    override val description =
        "Sends a follow-up email to gather missing qualification info. " +
        "Subject must make clear what info is still needed. " +
        "Cannot exceed the maximum follow-up limit (${config.maxFollowUps})."
    override val parameters = mapOf(
        "leadId"  to "The ID of the lead to follow up with.",
        "subject" to "Email subject line.",
        "body"    to "Full email body text asking for the missing information."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadId  = args["leadId"]?.toString()?.trim()  ?: return "Error: 'leadId' required."
        val subject = args["subject"]?.toString()?.trim() ?: return "Error: 'subject' required."
        val body    = args["body"]?.toString()?.trim()    ?: return "Error: 'body' required."

        val lead = store.get(leadId) ?: return "Error: Lead '$leadId' not found."

        // ── POLICY: No email while human escalation is pending ─────────────────
        if (lead.status == LeadStatus.ESCALATED_HUMAN) {
            return "POLICY_BLOCKED [NO_EMAIL_ESCALATED]: Lead is awaiting human escalation response. Resolve escalation first."
        }

        // ── POLICY: Max follow-ups ─────────────────────────────────────────────
        if (lead.followUpCount >= config.maxFollowUps) {
            val msg = "POLICY_BLOCKED [MAX_FOLLOW_UPS]: Reached maximum ${config.maxFollowUps} follow-ups for lead ${lead.name}. You must either disqualify the lead or escalate."
            auditLog.log(AuditEvent(AuditEventType.POLICY_BLOCKED, leadId,
                mapOf("policy" to "MAX_FOLLOW_UPS", "count" to lead.followUpCount)))
            return msg
        }

        emailService.sendEmail(lead.email, subject, body)
        lead.followUpCount++
        lead.status = LeadStatus.AWAITING_REPLY
        lead.emailThread.add(EmailMessage(EmailDirection.OUTBOUND, subject, body))

        auditLog.log(AuditEvent(AuditEventType.EMAIL_SENT, leadId,
            mapOf("type" to "FOLLOW_UP", "followUpCount" to lead.followUpCount, "subject" to subject)))

        return "Follow-up #${lead.followUpCount} sent to ${lead.name}. " +
               "${config.maxFollowUps - lead.followUpCount} follow-ups remaining."
    }
}
