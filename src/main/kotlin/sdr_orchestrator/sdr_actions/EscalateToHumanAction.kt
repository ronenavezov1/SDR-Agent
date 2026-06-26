package org.example.sdr_orchestrator.sdr_actions

import org.example.agent.Action
import org.example.sdr.*
import org.example.sdr_orchestrator.OrchestratorContext

class EscalateToHumanAction(private val ctx: OrchestratorContext) : Action {

    override val name = "escalateToHuman"
    override val description =
        "Escalates a lead for human review. MUST be called if the lead mentions pricing, " +
        "discounts, costs, or any sensitive commercial topic. After calling this, STOP."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead to escalate.",
        "reason" to "Why this needs human review.",
        "triggerMessage" to "The exact message excerpt that triggered this escalation."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val reason = args["reason"]?.toString()?.trim() ?: return "Error: 'reason' required."
        val triggerMessage = args["triggerMessage"]?.toString()?.trim() ?: ""

        val lead = ctx.getLead() ?: return "Error: Lead not found."

        if (lead.status is LeadStatus.Escalated) {
            val existing = (lead.status as LeadStatus.Escalated).escalation
            return "Lead '${lead.name}' is already escalated. Reason: ${existing.reason}"
        }

        lead.status = LeadStatus.Escalated(HumanEscalation(reason = reason, triggerMessage = triggerMessage))
        ctx.saveLead(lead)
        ctx.logEvent(Event.HumanEscalationTriggered(leadEmail = ctx.leadEmail, reason = reason, triggerMessage = triggerMessage))

        return "ESCALATED: ${lead.name} is now awaiting human review. Reason: $reason. STOP — do not continue processing this lead."
    }
}
