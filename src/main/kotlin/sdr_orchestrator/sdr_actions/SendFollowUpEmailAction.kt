package org.example.sdr_orchestrator.sdr_actions

import org.example.agent.Action
import org.example.sdr.*
import org.example.sdr_orchestrator.OrchestratorContext

class SendFollowUpEmailAction(private val ctx: OrchestratorContext) : Action {

    override val name = "sendFollowUpEmail"
    override val description =
        "Sends a follow-up email to gather missing qualification info. " +
        "Cannot exceed the maximum follow-up limit (${ctx.config.maxFollowUps})."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead to follow up with.",
        "subject" to "Email subject line.",
        "body" to "Full email body text asking for the missing information."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val subject = args["subject"]?.toString()?.trim() ?: return "Error: 'subject' required."
        val body = args["body"]?.toString()?.trim() ?: return "Error: 'body' required."

        val lead = ctx.getLead() ?: return "Error: Lead not found."

        if (lead.status is LeadStatus.Escalated) {
            return "POLICY_BLOCKED [NO_EMAIL_ESCALATED]: Lead is awaiting human review. Resolve escalation first."
        }

        if (lead.followUpCount >= ctx.config.maxFollowUps) {
            ctx.logEvent(Event.PolicyBlocked(
                leadEmail = ctx.leadEmail,
                policyName = "MAX_FOLLOW_UPS",
                reason = "Reached ${ctx.config.maxFollowUps} follow-ups for ${lead.name}"
            ))
            return "POLICY_BLOCKED [MAX_FOLLOW_UPS]: Maximum ${ctx.config.maxFollowUps} follow-ups reached. Disqualify or escalate."
        }

        ctx.sendEmail(lead.email, subject, body)
        lead.followUpCount++
        lead.status = LeadStatus.AwaitingClientResponse
        lead.emailThread.add(EmailMessage(EmailDirection.OUTBOUND, subject, body))
        ctx.saveLead(lead)
        ctx.logEvent(Event.EmailSent(leadEmail = ctx.leadEmail, subject = subject, body = body))

        return "Follow-up #${lead.followUpCount} sent to ${lead.name}. " +
            "${ctx.config.maxFollowUps - lead.followUpCount} follow-ups remaining."
    }
}
