package org.example.llm

import org.example.agent.AgentHistory
import org.example.agent.AgentCapability

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * מחלקת בסיס שמנהלת את כל המעטפת של הקריאה ל-LLM.
 * חוסכת את כל הכפילויות בין המודלים השונים וכוללת מנגנון הגנה מפני Rate Limits.
 */
abstract class BaseLlmClient(
    private val providerName: String,
    protected val apiKey: String,
    protected val modelName: String
) : LlmClient {
    @Throws(LlmSendException::class)
    override suspend fun sendMessage(
        agentId: String,
        systemPrompt: String,
        history: List<AgentHistory>,
        availableCapabilities: List<AgentCapability>
    ): LlmResponse {
        val requestPayload = buildRequestPayload(systemPrompt, history, availableCapabilities)
        val response = executeWithRetry(agentId, requestPayload)

        // Treat empty responses as failures so tryWithAllClients can try the next client
        if (response.textReply.isNullOrBlank() && response.functionCalls.isEmpty()) {
            throw LlmSendException(
                providerName = providerName,
                modelName = modelName,
                errorMessage = "$providerName returned an empty response for agent '$agentId'",
                agentId = agentId
            )
        }
        return response
    }

    /**
     * Exponential backoff retry for transient errors (429, 503, network IOException).
     * Fails fast on permanent errors (400, 401, 403) — no point retrying those.
     */
    @Throws(LlmSendException::class)
    private suspend fun executeWithRetry(
        agentId: String,
        payload: Any,
        maxRetries: Int = 3
    ): LlmResponse {
        var currentDelay = 1000L

        repeat(maxRetries) { attempt ->
            try {
                return executeNetworkCall(agentId, payload)
            } catch (e: Exception) {
                val msg = e.message ?: ""

                // Permanent errors — fail immediately, no retry
                val isPermanent = msg.contains("400") || msg.contains("401") ||
                                  msg.contains("403") || msg.contains("Unauthorized") ||
                                  msg.contains("Bad Request")
                if (isPermanent) throw e

                // Transient errors — retry with backoff
                val isTransient = msg.contains("429") || msg.contains("Too Many Requests") ||
                                  msg.contains("503") || msg.contains("Service Unavailable") ||
                                  e is IOException

                if (isTransient && attempt < maxRetries - 1) {
                    println("⚠️ Transient error from $providerName (${msg.take(60)}). Retrying in ${currentDelay}ms (attempt ${attempt + 1}/$maxRetries)…")
                    delay(currentDelay.milliseconds)
                    currentDelay *= 2
                } else {
                    throw LlmSendException(
                        agentId = agentId,
                        providerName = providerName,
                        modelName = modelName,
                        errorMessage = e.message ?: "Unknown error from $providerName"
                    )
                }
            }
        }
        throw LlmSendException(
            agentId = agentId,
            providerName = providerName,
            modelName = modelName,
            errorMessage = "LLM call failed after $maxRetries retries"
        )
    }

    /** פונקציות שכל מודל חייב לממש לפי חוקי ה-API שלו */
    protected abstract fun buildRequestPayload(
        system: String,
        history: List<AgentHistory>,
        capabilities: List<AgentCapability>
    ): Any // בדרך כלל יחזיר אובייקט שיעבור סריאליזציה ל-JSON

    @Throws(LlmSendException::class)
    protected abstract suspend fun executeNetworkCall(agentId: String, payload: Any): LlmResponse
}