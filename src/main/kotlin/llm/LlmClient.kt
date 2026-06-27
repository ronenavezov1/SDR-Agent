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
    val arguments: Map<String, Any> = emptyMap(),
    /** Opaque bytes returned by thinking models (e.g. Gemini 2.5+). Must be echoed back verbatim. */
    val thoughtSignature: ByteArray? = null
)

data class LlmSendException(
    val agentId: String,
    val providerName: String,
    val modelName: String,
    val errorMessage: String
) : Exception("LLM Send Error for agent '$agentId' with provider '$providerName' and model '$modelName': $errorMessage")

/**
 * A non-fatal LLM failure: the LLM client itself is healthy and responded, but the
 * output quality was insufficient (e.g. email draft rejected by the sanity checker).
 *
 * Unlike [LlmSendException], catching this should NOT mark the [LlmClient] as dead
 * in the pool — the client is still perfectly usable for other leads.
 */
data class LlmNonFatalException(
    val agentId: String,
    val errorMessage: String
) : Exception("LLM non-fatal failure from agent '$agentId': $errorMessage")
