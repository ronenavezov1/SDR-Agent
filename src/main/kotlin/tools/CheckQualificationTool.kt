package org.example.tools

import org.example.agent.Tool
import org.example.repositiories.SdrRepository
import org.example.sdr.QualificationConfig

class CheckQualificationTool(
    private val repository: SdrRepository,
    private val config: QualificationConfig
) : Tool {

    override val name = "checkQualification"
    override val description =
        "Checks whether a lead meets all qualification criteria. " +
        "Returns QUALIFIED, NEEDS_MORE_INFO, or DISQUALIFIED with reasons."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead to evaluate."
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val leadEmail = args["leadEmail"]?.toString()?.trim()
            ?: return "Error: 'leadEmail' argument is required."
        val lead = repository.getLead(leadEmail)
            ?: return "Error: Lead '$leadEmail' not found."

        val hardFailures  = mutableListOf<String>()
        val missingFields = mutableListOf<String>()

        val teamSize = lead.teamSize
        when {
            teamSize == null             -> missingFields.add("team_size")
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
            hardFailures.isNotEmpty()  -> "DISQUALIFIED: ${hardFailures.joinToString("; ")}."
            missingFields.isNotEmpty() ->
                "NEEDS_MORE_INFO: Still missing — ${missingFields.joinToString(", ")}. Send a follow-up."
            else ->
                "QUALIFIED: team_size=$teamSize, use_case=\"${lead.useCase}\", " +
                "commercial_intent=true. Ready to create a booking link."
        }
    }
}
