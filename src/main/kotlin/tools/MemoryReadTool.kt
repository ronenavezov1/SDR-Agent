package org.example.tools

import org.example.agent.AgentHistory
import org.example.agent.AiAgent
import org.example.agent.Tool

/**
 * Tool that reads and formats the conversation history of a target agent.
 *
 * ## How it works
 * The [WeatherOrchestrator] injects a [Map] of agent ID → [AiAgent] instances
 * at construction time. When the LLM calls this tool, it passes an `agentId`.
 * The tool looks up the agent and reads its **public** [AiAgent.history] property.
 *
 * No shared state manager is needed — this is possible because each [AiAgent]
 * exposes its own immutable history snapshot via a public property.
 *
 * @param agents Map of agent ID → AiAgent for all queryable agents.
 *               Injected by the orchestrator so this tool stays testable.
 */
class MemoryReadTool(private val agents: Map<String, AiAgent>) : Tool {

    override val name: String = "readAgentMemory"

    override val description: String =
        "Returns the full conversation history of a specified agent. " +
        "Pass the target agent's ID in the 'agentId' argument."

    override val parameters: Map<String, String> = mapOf(
        "agentId" to "The agent ID whose history to read. " +
                     "Available: topic-validator, planner, weather-checker, memory-reader."
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val agentId = args["agentId"]?.toString()?.trim()
            ?: return "Error: 'agentId' argument is required."

        val agent = agents[agentId]
            ?: return "Error: agent '$agentId' not found. " +
                      "Available IDs: ${agents.keys.sorted().joinToString()}"

        // Reads the immutable public snapshot — no state manager needed
        val memory = agent.history

        if (memory.isEmpty()) {
            return "No conversation history found for agent: '$agentId'"
        }

        val sb = StringBuilder(
            "=== Conversation history of '$agentId' (${memory.size} entries) ===\n"
        )
        memory.forEachIndexed { index, item ->
            val line = when (item) {
                is AgentHistory.UserInput           ->
                    "[${index + 1}] 👤 User:        ${item.text}"
                is AgentHistory.AiResponse          ->
                    "[${index + 1}] 🤖 Agent:       ${item.text}"
                is AgentHistory.ToolCallRequest     ->
                    "[${index + 1}] 🔧 Tool Call  → ${item.toolName}(${item.arguments})"
                is AgentHistory.ToolExecutionResult ->
                    "[${index + 1}] ✅ Tool Result  [${item.toolName}]: ${item.resultData}"
                is AgentHistory.SystemEvent         ->
                    "[${index + 1}] ⚙️  System:      ${item.eventDescription}"
                is AgentHistory.Summary             ->
                    "[${index + 1}] 🗜️  Summary:     ${item.text}"
            }
            sb.appendLine(line)
        }
        return sb.toString().trimEnd()
    }
}
