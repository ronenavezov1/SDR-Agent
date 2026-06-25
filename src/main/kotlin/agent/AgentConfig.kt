package org.example.agent

import org.example.llm.LlmClient

/**
 * Immutable configuration object for an [AiAgent].
 *
 * Encapsulates ALL dependencies an agent needs — following the
 * Dependency Injection (DI) pattern.  Nothing is hardcoded; everything
 * is passed in.  This makes each agent 100% testable: inject a mock
 * [LlmClient] and verify routing / tool-execution logic without any
 * live network calls.
 *
 * @param agentId        Unique string identifier (used in logs and by [MemoryReadTool]).
 * @param llmClient      The AI backend this agent will call.
 * @param systemPrompt   Defines the agent's persona, task, and boundaries.
 * @param tools          Read-only capabilities (CQRS "Query" side).
 * @param actions        Side-effecting capabilities (CQRS "Command" side).
 * @param timeoutMs      Max wall-clock time allowed for a single [AiAgent.processInput] call.
 *                       Heavy agents can be given more time; lightweight ones less.
 * @param maxDepth       Max ReAct reasoning depth before the loop is aborted.
 *                       Light agents can use a lower value (e.g. 3);
 *                       heavy/complex agents a higher one (e.g. 10). Defaults to 5.
 * @param maxHistorySize Context window size — how many recent history entries are sent to
 *                       the LLM on each turn. Older entries are silently dropped from the
 *                       request (but kept in [AiAgent.history]).
 *                       Light agents can use a smaller value (e.g. 10) to save tokens;
 *                       heavy/complex agents a larger one (e.g. 40). Defaults to 20.
 */
data class AgentConfig(
    val agentId: String,
    val llmClient: LlmClient,
    val systemPrompt: String,
    val tools: Map<String, Tool> = emptyMap(),
    val actions: Map<String, Action> = emptyMap(),
    val timeoutMs: Long = 60_000L,
    val maxDepth: Int = 5,
    val maxHistorySize: Int = 20
)
