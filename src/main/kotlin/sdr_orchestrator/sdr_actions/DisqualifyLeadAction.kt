package org.example.sdr_orchestrator.sdr_actions

import org.example.agent.Action
import org.example.sdr.*
import org.example.sdr_orchestrator.OrchestratorContext

class DisqualifyLeadAction(private val ctx: OrchestratorContext) : Action {

    override val name = "disqualifyLead"
    override val description =
        "Marks a lead as DISQUALIFIED with a clear reason. " +
        "Use when checkQualification returns DISQUALIFIED or the lead is clearly out of scope."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead to disqualify.",
        "reason" to "The specific reason (e.g. 'Team size 3 is below minimum of 10')."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val reason = args["reason"]?.toString()?.trim() ?: return "Error: 'reason' required."
        val lead = ctx.getLead() ?: return "Error: Lead not found."

        if (lead.status is LeadStatus.Disqualified)
            return "Lead '${lead.name}' is already disqualified."

        lead.status = LeadStatus.Disqualified(reason)
        ctx.saveLead(lead)
        ctx.logEvent(Event.LeadDisqualified(leadEmail = ctx.leadEmail, reason = reason))

        return "❌ Lead DISQUALIFIED: ${lead.name} (${lead.company}). Reason: $reason"
    }
}
