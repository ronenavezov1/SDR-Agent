package org.example.llm

import org.example.agent.AgentHistory
import org.example.agent.AgentCapability
import org.example.debug.DebugLogger

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Base LLM client — handles retry logic and the empty-response guard.
 * Concrete subclasses only need to implement [buildRequestPayload] and [executeNetworkCall].
 */
abstract class BaseLlmClient(
    final override val providerName: String,
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
        maxRetries: Int = 5,
        maxDelayMs: Long = 10_000L
    ): LlmResponse {
        var currentDelay = 2500L

        repeat(maxRetries) { attempt ->
            try {
                return executeNetworkCall(agentId, payload)
            } catch (e: Exception) {
                val msg = (e.message ?: "").lowercase()

                // Permanent errors — fail immediately, no retry, but still wrap as LlmSendException
                // so tryWithAllClients routes it correctly (not to orchestrationBug).
                // Use word-boundary-aware checks to avoid false positives (e.g. "1400" matching "400").
                val isPermanent = Regex("\\b400\\b").containsMatchIn(msg) ||
                                  Regex("\\b401\\b").containsMatchIn(msg) ||
                                  Regex("\\b403\\b").containsMatchIn(msg) ||
                                  msg.contains("unauthorized") ||
                                  msg.contains("bad request") ||
                                  msg.contains("invalid_argument") ||
                                  msg.contains("permission_denied")
                if (isPermanent) throw LlmSendException(
                    agentId = agentId,
                    providerName = providerName,
                    modelName = modelName,
                    errorMessage = e.message ?: "Permanent error from $providerName"
                )

                // Everything else is treated as transient — retry with backoff.
                // This covers 429, 503, IOException, RESOURCE_EXHAUSTED, quota errors,
                // and any unexpected errors. Only known permanent errors skip retries.
                if (attempt < maxRetries - 1) {
                    val delayMs = minOf(currentDelay, maxDelayMs)
                    DebugLogger.llmRetry(providerName, msg, delayMs, attempt + 1, maxRetries)
                    delay(delayMs.milliseconds)
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

    /** Each concrete client maps the shared history/capabilities into its own wire format. */
    protected abstract fun buildRequestPayload(
        system: String,
        history: List<AgentHistory>,
        capabilities: List<AgentCapability>
    ): Any

    @Throws(LlmSendException::class)
    protected abstract suspend fun executeNetworkCall(agentId: String, payload: Any): LlmResponse
}