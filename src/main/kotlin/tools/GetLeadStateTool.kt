package org.example.tools

import org.example.agent.Tool
import org.example.sdr.LeadStore

/** Read-only tool: returns the current state of a lead as a formatted string. */
class GetLeadStateTool(private val store: LeadStore) : Tool {

    override val name = "getLeadState"
    override val description =
        "Retrieves the current state of a lead: qualification data, status, email count, and escalation info."
    override val parameters = mapOf(
        "leadId" to "The ID of the lead whose state to retrieve."
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val leadId = args["leadId"]?.toString()?.trim()
            ?: return "Error: 'leadId' argument is required."
        val lead = store.get(leadId)
            ?: return "Error: Lead '$leadId' not found."
        return lead.toContextString()
    }
}