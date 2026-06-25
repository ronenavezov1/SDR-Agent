package org.example.tools

import org.example.agent.Tool
import org.example.repositiories.SdrRepository

class GetLeadStateTool(private val repository: SdrRepository) : Tool {

    override val name = "getLeadState"
    override val description =
        "Retrieves the current state of a lead: qualification data, status, email count, and escalation info."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead whose state to retrieve."
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val leadEmail = args["leadEmail"]?.toString()?.trim()
            ?: return "Error: 'leadEmail' argument is required."
        val lead = repository.getLead(leadEmail)
            ?: return "Error: Lead '$leadEmail' not found."
        return lead.toContextString()
    }
}
