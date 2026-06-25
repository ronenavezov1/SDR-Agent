package org.example.llm

import org.example.agent.AgentHistory
import org.example.agent.AgentCapability

interface LlmClient {
    suspend fun sendMessage(
        systemPrompt: String,
        history: List<AgentHistory>,
        availableCapabilities: List<AgentCapability>
    ): LlmResponse
}

data class LlmResponse(
    val textReply: String? = null,
    val functionCalls: List<FunctionCall> = emptyList()
)

data class FunctionCall(
    val functionName: String,
    val arguments: Map<String, Any> = emptyMap()
)
