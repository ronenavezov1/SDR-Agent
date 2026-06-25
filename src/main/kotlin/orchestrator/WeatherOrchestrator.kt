package org.example.orchestrator

import org.example.actions.AgentForwardAction
import org.example.llm.LlmClient
import org.example.agent.AgentConfig
import org.example.agent.AiAgent
import org.example.tools.MemoryReadTool
import org.example.tools.WeatherCheckTool

/**
 * WeatherOrchestrator — the conductor of the multi-agent pipeline.
 *
 * ## Architecture
 *
 * ```
 * User message
 *      │
 *      ▼
 * ┌─────────────────────────────────────────────────┐
 * │  router  (AiAgent)                              │
 * │                                                 │
 * │  LLM reads message + sees available Actions:    │
 * │   • askWeatherAgent(question)                   │
 * │   • askMemoryAgent(question)                    │
 * │                                                 │
 * │  Decides:                                       │
 * │   ├─ calls askWeatherAgent ─► weatherChecker ─► answer
 * │   ├─ calls askMemoryAgent  ─► memoryReader   ─► answer
 * │   └─ returns text directly ─────────────────── answer
 * └─────────────────────────────────────────────────┘
 * ```
 *
 * ## Why one agent instead of a validator + planner + specialists?
 * The LLM is smart enough to decide routing AND handle off-topic messages in a
 * single round-trip.  If the topic is relevant → it calls the matching Action
 * (which runs the specialist and returns its answer).  If irrelevant → it replies
 * directly with a polite explanation.  Zero extra agents, zero keyword tools.
 *
 * ## KISS principle
 * From the caller's perspective this is a black box: one `processQuery()` call,
 * one answer via callback.  All internal routing is invisible.
 *
 * @param llmClient The AI backend, injected and shared by all agents.
 */
class WeatherOrchestrator(private val llmClient: LlmClient) {

    // ── Specialist agents ─────────────────────────────────────────────────────
    // Declared first because the router references them via AgentForwardAction.

    /**
     * Weather Specialist — fetches and summarises weather data.
     * Has one Tool: 'getWeatherData'.
     */
    private val weatherChecker = AiAgent(
        AgentConfig(
            agentId      = "weather-checker",
            llmClient    = llmClient,
            systemPrompt = """
                You are a friendly weather assistant.
                Always call the 'getWeatherData' tool with the location in the 'location' argument.
                Summarise the result in a clear, helpful, and concise answer.
            """.trimIndent(),
            tools = mapOf("getWeatherData" to WeatherCheckTool())
        )
    )

    /**
     * Memory Inspector — reads and presents the conversation history of any agent.
     * Receives direct [AiAgent] references so it can read their public [AiAgent.history].
     * Declared after [weatherChecker] so it can be passed into [MemoryReadTool].
     */
    private val memoryReader = AiAgent(
        AgentConfig(
            agentId      = "memory-reader",
            llmClient    = llmClient,
            systemPrompt = """
                You are a memory inspector.
                Call the 'readAgentMemory' tool with the agentId the user requested.
                Available IDs: router, weather-checker, memory-reader.
                If the user did not specify an agent, use "weather-checker" as the default.
                Present the memory in a clear, readable format.
            """.trimIndent(),
            tools = mapOf(
                "readAgentMemory" to MemoryReadTool(
                    // "memory-reader" itself is omitted to avoid a circular reference at init time
                    agents = mapOf(
                        "weather-checker" to weatherChecker
                        // router is added lazily in init {} block below
                    )
                )
            )
        )
    )

    // ── Router agent ──────────────────────────────────────────────────────────
    // Created last so weatherChecker and memoryReader are already initialised.

    /**
     * Router — the single entry point for all user messages.
     *
     * Its LLM decides between two Actions or a direct text reply:
     *  - **askWeatherAgent**: forward the question to [weatherChecker]
     *  - **askMemoryAgent**:  forward the question to [memoryReader]
     *  - **no action called**: LLM writes a direct answer (e.g. for off-topic input)
     *
     * The system prompt is the ONLY place where routing rules live.
     * Add a new specialist → add one [AgentForwardAction] + one line in the prompt.
     */
    private val router = AiAgent(
        AgentConfig(
            agentId      = "router",
            llmClient    = llmClient,
            systemPrompt = """
                You are an intelligent assistant dispatcher.
                You have access to two specialist actions:

                1. askWeatherAgent  — handles anything about weather: current conditions,
                   forecasts, temperature, rain, wind, humidity, climate for any location.

                2. askMemoryAgent   — handles requests to view conversation history or
                   memory of any agent in the system.

                Rules:
                • If the user's message clearly fits one of the two specialists,
                  call that action and pass the user's message VERBATIM as the
                  'question' argument. Return the specialist's answer directly —
                  do NOT add commentary or rephrase it.
                • If the message does not fit any specialist (e.g. math, coding,
                  unrelated topics), reply directly in a helpful and friendly tone.
                  Briefly mention what you CAN help with.
            """.trimIndent(),
            actions = mapOf(
                "askWeatherAgent" to AgentForwardAction(
                    targetAgent = weatherChecker,
                    name = "askWeatherAgent",
                    description = "Forwards the question to the weather specialist agent. " +
                            "Use for anything about weather, forecasts, or climate."
                ),
                "askMemoryAgent" to AgentForwardAction(
                    targetAgent = memoryReader,
                    name = "askMemoryAgent",
                    description = "Forwards the question to the memory inspector agent. " +
                            "Use when the user wants to see conversation history or agent memory."
                )
            )
        )
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Processes a user message through the routing pipeline.
     *
     * From the caller's perspective: string in, string out via [onResponse].
     * Suspends until the answer is ready (may involve one or more LLM + tool calls).
     *
     * @param question   The raw user input.
     * @param onResponse Suspend callback invoked exactly once with the final answer.
     */
    suspend fun processQuery(question: String): String {
        return router.processInput(question)
    }

    /**
     * Clears the conversation history of every agent.
     * Safe to call between sessions without recreating the orchestrator.
     */
    fun clearHistory() {
        listOf(router, weatherChecker, memoryReader).forEach { it.clearHistory() }
    }
}
