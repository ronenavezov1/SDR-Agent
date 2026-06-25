package org.example.llm

import org.example.agent.AgentHistory
import org.example.agent.AgentCapability

/**
 * מחלקת בסיס שמנהלת את כל המעטפת של הקריאה ל-LLM.
 * חוסכת את כל הכפילויות בין המודלים השונים.
 */
abstract class BaseLlmClient(
    private val providerName: String,
    protected val apiKey: String,
    protected val modelName: String
) : LlmClient {

    override suspend fun sendMessage(
        agentId: String,
        systemPrompt: String,
        history: List<AgentHistory>,
        availableCapabilities: List<AgentCapability>
    ): LlmResponse {

        return try {
            val requestPayload = buildRequestPayload(systemPrompt, history, availableCapabilities)

            executeNetworkCall(agentId, requestPayload)

        } catch (e: Exception) {
            println("❌ [$providerName] [$agentId] Network or Parsing Error: ${e.localizedMessage}")
            LlmResponse(
                textReply = "System Error from $providerName: ${e.message}",
                functionCalls = emptyList()
            )
        }
    }

    /** פונקציות שכל מודל חייב לממש לפי חוקי ה-API שלו */
    protected abstract fun buildRequestPayload(
        system: String,
        history: List<AgentHistory>,
        capabilities: List<AgentCapability>
    ): Any // בדרך כלל יחזיר אובייקט שיעבור סריאליזציה ל-JSON

    protected abstract suspend fun executeNetworkCall(agentId: String, payload: Any): LlmResponse
}