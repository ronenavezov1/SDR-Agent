package org.example.actions

import org.example.agent.Action
import org.example.repositiories.SdrRepository
import org.example.sdr.*

/**
 * Action: escalates a lead to a human reviewer.
 *
 * Sets status to [LeadStatus.Escalated] — which embeds the [HumanEscalation] data
 * directly. Other actions check `is LeadStatus.Escalated` and refuse to proceed,
 * providing code-level enforcement beyond the system prompt instruction.
 */
class EscalateToHumanAction(
    private val repository: SdrRepository
) : Action {

    override val name = "escalateToHuman"
    override val description =
        "Escalates a lead for human review. MUST be called if the lead mentions pricing, " +
        "discounts, costs, or any sensitive commercial topic. After calling this, STOP."
    override val parameters = mapOf(
        "leadEmail"      to "The email address of the lead to escalate.",
        "reason"         to "Why this needs human review.",
        "triggerMessage" to "The exact message excerpt that triggered this escalation."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadEmail      = args["leadEmail"]?.toString()?.trim()      ?: return "Error: 'leadEmail' required."
        val reason         = args["reason"]?.toString()?.trim()         ?: return "Error: 'reason' required."
        val triggerMessage = args["triggerMessage"]?.toString()?.trim() ?: ""

        val lead = repository.getLead(leadEmail) ?: return "Error: Lead '$leadEmail' not found."

        // Idempotent
        if (lead.status is LeadStatus.Escalated) {
            val existing = (lead.status as LeadStatus.Escalated).escalation
            return "Lead '${lead.name}' is already escalated. Reason: ${existing.reason}"
        }

        lead.status = LeadStatus.Escalated(HumanEscalation(reason = reason, triggerMessage = triggerMessage))
        repository.logEvent(Event.HumanEscalationTriggered(leadEmail = leadEmail, reason = reason, triggerMessage = triggerMessage))

        return "ESCALATED: ${lead.name} is now awaiting human review. Reason: $reason. STOP — do not continue processing this lead."
    }
}

