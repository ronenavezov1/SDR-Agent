package org.example.sdr_orchestrator.sdr_actions

import org.example.agent.Action
import org.example.sdr.*
import org.example.sdr_orchestrator.OrchestratorContext

class UpdateQualificationAction(private val ctx: OrchestratorContext) : Action {

    override val name = "updateQualification"
    override val description =
        "Stores qualification information extracted from a lead's reply. " +
        "Only pass fields you actually learned from the reply — omit unknowns."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead to update.",
        "useCase" to "(optional) The lead's described use case or problem.",
        "teamSize" to "(optional) Team or company headcount as an integer.",
        "commercialIntent" to "(optional) 'true' if actively evaluating/buying, 'false' if not."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val lead = ctx.getLead() ?: return "Error: Lead not found."

        args["useCase"]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?.let { lead.useCase = it }
        args["teamSize"]?.toString()?.trim()?.toIntOrNull()
            ?.let { lead.teamSize = it }
        args["commercialIntent"]?.toString()?.trim()
            ?.let { lead.commercialIntent = it.lowercase() in listOf("true", "yes", "1") }

        ctx.saveLead(lead)
        ctx.logEvent(Event.QualificationUpdated(
            leadEmail = ctx.leadEmail,
            useCase = lead.useCase,
            teamSize = lead.teamSize,
            commercialIntent = lead.commercialIntent
        ))

        return "Qualification updated for ${lead.name}: " +
            "useCase=${lead.useCase}, teamSize=${lead.teamSize}, commercialIntent=${lead.commercialIntent}"
    }
}
