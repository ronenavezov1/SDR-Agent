package org.example.tools

import org.example.agent.Tool
import org.example.sdr.LeadStore
import org.example.sdr.QualificationConfig

/**
 * Read-only tool: evaluates whether a lead passes all qualification criteria.
 *
 * Returns one of three outcomes:
 *  - QUALIFIED          — all criteria met; agent should call createBookingLink
 *  - NEEDS_MORE_INFO    — some data is still missing; agent should send a follow-up
 *  - DISQUALIFIED       — lead fails a hard criterion; agent should call disqualifyLead
 */
class CheckQualificationTool(
    private val store: LeadStore,
    private val config: QualificationConfig
) : Tool {

    override val name = "checkQualification"
    override val description =
        "Checks whether a lead meets all qualification criteria. " +
        "Returns QUALIFIED, NEEDS_MORE_INFO, or DISQUALIFIED with reasons."
    override val parameters = mapOf(
        "leadId" to "The ID of the lead to evaluate."
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val leadId = args["leadId"]?.toString()?.trim()
            ?: return "Error: 'leadId' argument is required."
        val lead = store.get(leadId)
            ?: return "Error: Lead '$leadId' not found."

        val hardFailures = mutableListOf<String>()
        val missingFields = mutableListOf<String>()

        val teamSize = lead.qualificationData.teamSize
        when {
            teamSize == null -> missingFields.add("team_size")
            teamSize < config.minTeamSize ->
                hardFailures.add("team size ($teamSize) is below minimum (${config.minTeamSize})")
        }

        if (config.requireUseCase && lead.qualificationData.useCase == null)
            missingFields.add("use_case")

        when {
            lead.qualificationData.commercialIntent == null && config.requireCommercialIntent ->
                missingFields.add("commercial_intent")
            lead.qualificationData.commercialIntent == false ->
                hardFailures.add("lead has explicitly no commercial intent")
        }

        return when {
            hardFailures.isNotEmpty() ->
                "DISQUALIFIED: ${hardFailures.joinToString("; ")}."

            missingFields.isNotEmpty() ->
                "NEEDS_MORE_INFO: Still missing — ${missingFields.joinToString(", ")}. " +
                "Send a follow-up to collect this information."

            else ->
                "QUALIFIED: All criteria met. " +
                "team_size=${teamSize}, " +
                "use_case=\"${lead.qualificationData.useCase}\", " +
                "commercial_intent=true. Ready to create a booking link."
        }
    }
}