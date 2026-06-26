package org.example.sdr_orchestrator.sdt_tools

import org.example.agent.Tool
import org.example.sdr_orchestrator.OrchestratorContext

class CheckQualificationTool(private val ctx: OrchestratorContext) : Tool {

    override val name = "checkQualification"
    override val description =
        "Checks whether a lead meets all qualification criteria. " +
        "Returns QUALIFIED, NEEDS_MORE_INFO, or DISQUALIFIED with reasons."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead to evaluate."
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val lead = ctx.getLead() ?: return "Error: Lead '${ctx.leadEmail}' not found."
        val config = ctx.config

        val hardFailures = mutableListOf<String>()
        val missingFields = mutableListOf<String>()

        val teamSize = lead.teamSize
        when {
            teamSize == null -> missingFields.add("team_size")
            teamSize < config.minTeamSize ->
                hardFailures.add("team size ($teamSize) is below minimum (${config.minTeamSize})")
        }

        if (config.requireUseCase && lead.useCase == null)
            missingFields.add("use_case")

        when {
            lead.commercialIntent == null && config.requireCommercialIntent ->
                missingFields.add("commercial_intent")
            lead.commercialIntent == false ->
                hardFailures.add("lead has no commercial intent")
        }

        return when {
            hardFailures.isNotEmpty() -> "DISQUALIFIED: ${hardFailures.joinToString("; ")}."
            missingFields.isNotEmpty() ->
                "NEEDS_MORE_INFO: Still missing — ${missingFields.joinToString(", ")}. Send a follow-up."
            else ->
                "QUALIFIED: team_size=$teamSize, use_case=\"${lead.useCase}\", " +
                    "commercial_intent=true. Ready to create a booking link."
        }
    }
}
