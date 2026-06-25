package org.example.agent

/**
 * Base contract that every agent capability must satisfy.
 *
 * The [name] is the exact function name the LLM will use when invoking it.
 * The [description] and [parameters] are compiled into a FunctionDeclaration
 * that is sent to the LLM so it knows *when* and *how* to call the capability.
 */
interface AgentCapability {

    /** Unique function name — must match what the LLM is told to call. */
    val name: String

    /** Plain-English explanation of what this capability does. */
    val description: String

    /**
     * Parameter definitions: key = parameter name, value = plain-English description.
     * The [LlmClient] adapter uses this map to build a JSON Schema for the function.
     */
    val parameters: Map<String, String> get() = emptyMap()
}

// ── CQRS Query side ──────────────────────────────────────────────────────────

/**
 * Read-only capability — no side effects, just fetches and returns data.
 *
 * Examples: weather API call, question classifier, web search.
 */
interface Tool : AgentCapability {

    /**
     * Execute the tool with the arguments chosen by the LLM.
     * @param args Key→value map from the LLM's function-call payload.
     * @return A string result that is fed back to the LLM as context.
     */
    suspend fun execute(args: Map<String, Any>): String
}

// ── CQRS Command side ────────────────────────────────────────────────────────

/**
 * State-mutating capability — may have side effects outside the agent.
 *
 * Examples: booking a calendar event, sending a notification, writing a file.
 * Receives the [agentId] so the implementation can log who triggered it.
 */
interface Action : AgentCapability {

    /**
     * Perform the action requested by the LLM.
     * @param agentId The ID of the agent invoking this action (for audit/logging).
     * @param args    Key→value map from the LLM's function-call payload.
     * @return A string result that is fed back to the LLM.
     */
    suspend fun perform(agentId: String, args: Map<String, Any>): String
}
