package org.example.actions

import org.example.agent.Action
import org.example.repositiories.SdrRepository
import org.example.sdr.*

class DisqualifyLeadAction(
    private val repository: SdrRepository
) : Action {

    override val name = "disqualifyLead"
    override val description =
        "Marks a lead as DISQUALIFIED with a clear reason. " +
        "Use when checkQualification returns DISQUALIFIED or the lead is clearly out of scope."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead to disqualify.",
        "reason"    to "The specific reason (e.g. 'Team size 3 is below minimum of 10')."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadEmail = args["leadEmail"]?.toString()?.trim() ?: return "Error: 'leadEmail' required."
        val reason    = args["reason"]?.toString()?.trim()    ?: return "Error: 'reason' required."

        val lead = repository.getLead(leadEmail) ?: return "Error: Lead '$leadEmail' not found."

        if (lead.status is LeadStatus.Disqualified)
            return "Lead '${lead.name}' is already disqualified."

        lead.status = LeadStatus.Disqualified
        repository.logEvent(Event.LeadDisqualified(leadEmail = leadEmail, reason = reason))

        return "❌ Lead DISQUALIFIED: ${lead.name} (${lead.company}). Reason: $reason"
    }
}

