package org.example.actions

import org.example.agent.Action
import org.example.sdr.*

/** Action: marks a lead as disqualified with an explicit reason. */
class DisqualifyLeadAction(
    private val store: LeadStore,
    private val auditLog: AuditLog
) : Action {

    override val name = "disqualifyLead"
    override val description =
        "Marks a lead as DISQUALIFIED with a clear reason. " +
        "Use when checkQualification returns DISQUALIFIED or when the lead is clearly out of scope."
    override val parameters = mapOf(
        "leadId" to "The ID of the lead to disqualify.",
        "reason" to "The specific reason (e.g. 'Team size 3 is below minimum of 10', 'No commercial intent')."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadId = args["leadId"]?.toString()?.trim() ?: return "Error: 'leadId' required."
        val reason = args["reason"]?.toString()?.trim() ?: return "Error: 'reason' required."

        val lead = store.get(leadId) ?: return "Error: Lead '$leadId' not found."

        if (lead.status == LeadStatus.DISQUALIFIED)
            return "Lead '${lead.name}' is already disqualified."

        lead.status = LeadStatus.DISQUALIFIED

        auditLog.log(AuditEvent(AuditEventType.LEAD_DISQUALIFIED, leadId,
            mapOf("reason" to reason, "name" to lead.name, "company" to lead.company)))

        return "❌ Lead DISQUALIFIED: ${lead.name} (${lead.company}). Reason: $reason"
    }
}
