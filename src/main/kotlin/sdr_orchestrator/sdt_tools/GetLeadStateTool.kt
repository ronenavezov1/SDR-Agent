package org.example.sdr_orchestrator.sdt_tools

import org.example.agent.Tool
import org.example.sdr_orchestrator.OrchestratorContext

class GetLeadStateTool(private val ctx: OrchestratorContext) : Tool {

    override val name = "getLeadState"
    override val description =
        "Retrieves the current state of a lead: qualification data, status, email count, and escalation info."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the lead whose state to retrieve."
    )

    override suspend fun execute(args: Map<String, Any>): String =
        ctx.getLead()?.toContextString() ?: "Error: Lead '${ctx.leadEmail}' not found."
}
