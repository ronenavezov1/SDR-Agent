package org.example.llm

import org.example.agent.AgentHistory
import org.example.agent.AgentCapability

class OpenAiLlmClient(apiKey: String, modelName: String) :
    BaseLlmClient(providerName = "OpenAI", apiKey, modelName) {

    override fun buildRequestPayload(
        system: String,
        history: List<AgentHistory>,
        capabilities: List<AgentCapability>
    ): Any {
        // כאן אתה כותב רק את הלוגיקה שממירה את ה-history למערך של role: "user"/"assistant"
        // ואת ה-capabilities ל-JSON Schema של OpenAI Tools
        return mapOf(
            "agent" to modelName,
            "messages" to listOf<Any>() // TODO: implement OpenAI message mapping
        )
    }

    override suspend fun executeNetworkCall(payload: Any): LlmResponse {
        // כאן מתבצעת קריאת ה-POST ל: https://api.openai.com/v1/chat/completions
        // ולאחר מכן מחזירים LlmResponse
        return LlmResponse(textReply = "Mocked OpenAI Answer")
    }
}