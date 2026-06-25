package org.example.actions

import org.example.agent.Action
import org.example.sdr.*

/**
 * Action: escalates a lead to a human agent for review.
 *
 * MUST be called whenever the lead raises pricing, discounts, or other
 * sensitive topics. After calling this tool, the agent MUST stop processing
 * that lead until a human resolves the escalation.
 *
 * The tool sets the lead status to ESCALATED_HUMAN. Other tools (sendFollowUpEmail,
 * createBookingLink) will refuse to execute while this status is active — providing
 * defense-in-depth beyond the system prompt instruction.
 */
class EscalateToHumanAction(
    private val store: LeadStore,
    private val auditLog: AuditLog
) : Action {

    override val name = "escalateToHuman"
    override val description =
        "Escalates a lead for human review. MUST be called if the lead mentions pricing, " +
        "discounts, costs, or any sensitive commercial topic. After calling this, STOP and " +
        "do not send more emails or make further decisions for this lead."
    override val parameters = mapOf(
        "leadId"         to "The ID of the lead to escalate.",
        "reason"         to "Why this needs human review (e.g. 'Lead asked about pricing and discounts').",
        "triggerMessage" to "The exact message excerpt that triggered this escalation."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadId         = args["leadId"]?.toString()?.trim()         ?: return "Error: 'leadId' required."
        val reason         = args["reason"]?.toString()?.trim()         ?: return "Error: 'reason' required."
        val triggerMessage = args["triggerMessage"]?.toString()?.trim() ?: ""

        val lead = store.get(leadId) ?: return "Error: Lead '$leadId' not found."

        // Idempotent: already escalated
        if (lead.status == LeadStatus.ESCALATED_HUMAN) {
            return "Lead '${lead.name}' is already escalated (pending human response). " +
                   "Reason: ${lead.escalation?.reason ?: reason}"
        }

        lead.escalation = HumanEscalation(reason = reason, triggerMessage = triggerMessage)
        lead.status = LeadStatus.ESCALATED_HUMAN

        auditLog.log(AuditEvent(AuditEventType.HUMAN_ESCALATION_TRIGGERED, leadId,
            mapOf("reason" to reason, "trigger" to triggerMessage.take(200))))

        println()
        println("  ┌─ 🚨 HUMAN ESCALATION REQUIRED ──────────────────────────────")
        println("  │  Lead:    ${lead.name} (${lead.company})")
        println("  │  Email:   ${lead.email}")
        println("  │  Reason:  $reason")
        println("  │  Trigger: \"${triggerMessage.take(120)}\"")
        println("  │")
        println("  │  Respond with:  resolve ${lead.id}")
        println("  └─────────────────────────────────────────────────────────────")
        println()

        return "ESCALATED: Lead ${lead.name} is now awaiting human review. " +
               "Reason: $reason. " +
               "STOP processing this lead. The human will respond via the CLI."
    }
}
