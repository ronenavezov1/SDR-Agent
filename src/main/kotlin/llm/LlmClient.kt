package org.example.llm

import org.example.agent.AgentHistory
import org.example.agent.AgentCapability
import kotlin.jvm.Throws

interface LlmClient {
    val providerName: String

    @Throws(LlmSendException::class)
    suspend fun sendMessage(
        agentId: String,
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

data class LlmSendException(
    val agentId: String,
    val providerName: String,
    val modelName: String,
    val errorMessage: String
) : Exception("LLM Send Error for agent '$agentId' with provider '$providerName' and model '$modelName': $errorMessage")
