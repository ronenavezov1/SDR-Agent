package org.example.actions

import org.example.agent.Action
import org.example.sdr.*

/**
 * Action: stores qualification data extracted by the LLM from a lead's reply.
 *
 * The LLM is the extractor (it reads the reply text in context and decides what
 * was communicated). This tool is the write path — it persists the extraction
 * result into the lead's QualificationData, making it visible to subsequent tools.
 */
class UpdateQualificationAction(
    private val store: LeadStore,
    private val auditLog: AuditLog
) : Action {

    override val name = "updateQualification"
    override val description =
        "Stores qualification information extracted from a lead's reply. " +
        "Only pass fields you actually learned from the reply — omit unknowns."
    override val parameters = mapOf(
        "leadId"            to "The ID of the lead to update.",
        "useCase"           to "(optional) The lead's described use case or problem.",
        "teamSize"          to "(optional) Team or company headcount as an integer.",
        "commercialIntent"  to "(optional) 'true' if the lead is actively evaluating/buying, 'false' if not."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadId = args["leadId"]?.toString()?.trim() ?: return "Error: 'leadId' required."
        val lead = store.get(leadId) ?: return "Error: Lead '$leadId' not found."

        val updates = mutableMapOf<String, Any>()

        args["useCase"]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let {
            lead.qualificationData.useCase = it
            updates["useCase"] = it
        }
        args["teamSize"]?.toString()?.trim()?.toIntOrNull()?.let {
            lead.qualificationData.teamSize = it
            updates["teamSize"] = it
        }
        args["commercialIntent"]?.toString()?.trim()?.let { raw ->
            val intent = raw.lowercase() in listOf("true", "yes", "1")
            lead.qualificationData.commercialIntent = intent
            updates["commercialIntent"] = intent
        }

        if (updates.isEmpty())
            return "No qualification data updated (no valid fields provided). Provide at least one: useCase, teamSize, or commercialIntent."

        lead.status = LeadStatus.QUALIFYING
        auditLog.log(AuditEvent(AuditEventType.QUALIFICATION_UPDATED, leadId, updates))

        return "Qualification updated for ${lead.name}: $updates. " +
               "Current: useCase=${lead.qualificationData.useCase}, " +
               "teamSize=${lead.qualificationData.teamSize}, " +
               "commercialIntent=${lead.qualificationData.commercialIntent}"
    }
}
