package org.example.agent

import kotlinx.coroutines.withTimeout
import org.example.debug.DebugLogger
import org.example.llm.FunctionCall
import org.example.llm.LlmSendException
import kotlin.time.Duration.Companion.milliseconds

/**
 * A fully self-contained, standalone AI agent.
 *
 * ## Design principles
 *
 * **Encapsulation:** The conversation history ([_history]) and status ([_status])
 * are private. Only this agent writes to them. External code reads through the
 * immutable [history] and [status] properties — preventing accidental corruption
 * from outside.
 *
 * **Dependency Injection:** The agent receives an [AgentConfig] with everything
 * it needs: the LLM client, system prompt, tools, and actions.  This makes the
 * agent 100% testable — inject a mock [LlmClient] to verify routing logic without
 * a live network connection.
 *
 * **Standalone / no shared state:** Each agent owns its own history.
 * There is no global state manager. An orchestrator coordinates
 * multiple agents by reading their public [history] / [status] properties.
 *
 * ## Usage
 * ```kotlin
 * val agent = AiAgent(config)
 * agent.processInput("What is the weather in Tel Aviv?") { answer ->
 *     println(answer)   // called exactly once when the agent has a final answer
 * }
 * ```
 *
 * @param config All dependencies, injected at construction time.
 */
class AiAgent(private val config: AgentConfig) {

    // ── Private mutable state ─────────────────────────────────────────────────

    /** Full conversation history — only this agent appends to it. */
    private val _history = mutableListOf<AgentHistory>()

    /** Current operational status — only this agent changes it. */
    private var _status: AgentStatus = AgentStatus.Idle

    // ── Public read-only interface ────────────────────────────────────────────

    /**
     * An immutable snapshot of the conversation history.
     * Returns a new list each time — callers cannot mutate it.
     */
    val history: List<AgentHistory> get() = _history.toList()

    /** The current status of this agent. Read-only from the outside. */
    val status: AgentStatus get() = _status

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Processes a user message through the full ReAct loop and returns the agent's final answer.
     *
     * If the agent is currently busy ([AgentStatus] is not [AgentStatus.Idle]), the call is
     * rejected immediately and returns a message describing the current status with a snapshot
     * of the recent history window.
     *
     * The loop is guarded by [AgentConfig.timeoutMs]. If the LLM or any tool takes too long the
     * coroutine is cancelled and an error string is returned.
     * Non-LLM exceptions propagate up to the caller (e.g. [SdrRepository.tryWithAllClients]).
     *
     * @param input The user's message as a plain string.
     */
    suspend fun processInput(input: String) : String{
        if (_status is AgentStatus.Error) {
            _status = AgentStatus.Idle
        }

        if (_status is AgentStatus.Thinking || _status is AgentStatus.Working) {
            val statusDesc = when (val s = _status) {
                is AgentStatus.Thinking -> "thinking"
                is AgentStatus.Working  -> "working on: ${s.toolName}"
                else                    -> "busy"
            }
            val window = _history.takeLast(config.maxHistorySize)
            val historyDump = buildString {
                appendLine("=== Current activity of '${config.agentId}' (last ${window.size} entries) ===")
                window.forEachIndexed { idx, item ->
                    val line = when (item) {
                        is AgentHistory.UserInput           -> "[${idx + 1}] 👤 User:         ${item.text}"
                        is AgentHistory.AiResponse          -> "[${idx + 1}] 🤖 Agent:        ${item.text}"
                        is AgentHistory.ToolCallRequest     -> "[${idx + 1}] 🔧 Tool Call  →  ${item.toolName}(${item.arguments})"
                        is AgentHistory.ToolExecutionResult -> "[${idx + 1}] ✅ Tool Result   [${item.toolName}]: ${item.resultData}"
                        is AgentHistory.SystemEvent         -> "[${idx + 1}] ⚙️  System:       ${item.eventDescription}"
                        is AgentHistory.Summary             -> "[${idx + 1}] 🗜️  Summary:      ${item.text}"
                    }
                    appendLine(line)
                }
            }
            return "🚫 Agent '${config.agentId}' is busy ($statusDesc) and cannot accept new input.\n$historyDump"
        }

        DebugLogger.agentStart(config.agentId, config.workOnId, config.llmClient.providerName, input)

        _history.add(AgentHistory.UserInput(text = input))
        try {
            val result = withTimeout(config.timeoutMs.milliseconds) {
                runReactLoop(depth = 0)
            }
            DebugLogger.agentDone(config.agentId, config.workOnId, config.llmClient.providerName, _history.toList(), result)
            return result
        } finally {
            _history.clear()
            _status = AgentStatus.Idle
        }
        // All other exceptions (LLM failures, network errors) propagate to tryWithAllClients
    }

    /**
     * Compresses this agent's conversation history into a single [AgentHistory.Summary].
     *
     * ## When to call this
     * Call from the **orchestrator** (or any external coordinator) when this agent's
     * [history] grows beyond a threshold — e.g. `agent.history.size > 40`.
     * The agent cannot trigger this itself: it is a deliberate external decision,
     * keeping the agent's own loop simple.
     *
     * ## What happens inside
     * 1. Takes the last [maxItems] history entries (or all of them if fewer exist).
     * 2. Serialises them into a plain-text transcript.
     * 3. Sends the transcript to the LLM with a summarisation instruction,
     *    inside a [withTimeout] coroutine — if the LLM takes longer than
     *    [timeoutMs] milliseconds the call is cancelled and the history is left
     *    untouched (fail-safe).
     * 4. On success: clears [_history] and inserts exactly one [AgentHistory.Summary].
     *
     * @param maxItems   How many recent history items to include in the summary request.
     *                   Older items are dropped to stay within the LLM's context window.
     *                   Defaults to 30.
     * @param timeoutMs  Maximum time in milliseconds allowed for the LLM call.
     *                   Defaults to 30 000 ms (30 seconds). On timeout the history is
     *                   left untouched (fail-safe).
     */
    private suspend fun summarizeHistory(
        maxItems:  Int  = 30,
        timeoutMs: Long = 30_000L
    ) {
        if (_history.isEmpty()) return

        DebugLogger.historySummarize(config.agentId, _history.size)

        // ── 1. Build a readable transcript of the most recent N items ─────────
        val window = _history.takeLast(maxItems)
        val transcript = buildString {
            window.forEach { item ->
                val line = when (item) {
                    is AgentHistory.UserInput           -> "USER:    ${item.text}"
                    is AgentHistory.AiResponse          -> "AGENT:   ${item.text}"
                    is AgentHistory.ToolCallRequest     -> "TOOL_CALL: ${item.toolName}(${item.arguments})"
                    is AgentHistory.ToolExecutionResult -> "TOOL_RESULT [${item.toolName}]: ${item.resultData}"
                    is AgentHistory.SystemEvent         -> "SYSTEM:  ${item.eventDescription}"
                    is AgentHistory.Summary             -> "PREVIOUS_SUMMARY: ${item.text}"
                }
                appendLine(line)
            }
        }

        // ── 2. Ask the LLM for a summary, with a hard timeout ─────────────────
        try {
            val summaryText = withTimeout(timeoutMs.milliseconds) {
                val response = config.llmClient.sendMessage(
                    agentId               = config.agentId,
                    systemPrompt = """
                        You are a memory compression assistant.
                        You will receive a conversation transcript.
                        Write a concise summary (max 5 sentences) that preserves:
                        - Every topic discussed
                        - Every decision or fact established
                        - Any outstanding questions or pending tasks
                        Do NOT include greetings or filler. Be dense and factual.
                    """.trimIndent(),
                    history               = listOf<AgentHistory>(AgentHistory.UserInput(transcript)),
                    availableCapabilities = emptyList()
                )
                response.textReply?.trim()
                    ?: error("LLM returned no text for summarisation request.")
            }

            // ── 3. Replace entire history with the single Summary entry ────────
            _history.clear()
            _history.add(AgentHistory.Summary(text = summaryText))
        } catch (e: LlmSendException) {
            DebugLogger.historySummarizeError(config.agentId, e)
        }
    }

    // ── ReAct loop ────────────────────────────────────────────────────────────

    /**
     * The ReAct (Reason + Act) loop — the core reasoning engine.
     *
     * Each iteration:
     *  1. Sends the current history to the LLM.
     *  2a. If the LLM requests tool calls → executes each tool sequentially,
     *      appends results to history, recurses (depth + 1).
     *  2b. If the LLM returns text → appends to history, returns the text.
     *
     * @param depth Recursion counter. Hard-stops at [AgentConfig.maxDepth] to prevent infinite loops.
     * @return The agent's final plain-text reply to the user.
     */
    @Throws(LlmSendException::class)
    private suspend fun runReactLoop(depth: Int): String {

        if (depth > config.maxDepth) {
            val msg = "[${config.agentId}] Aborted: reached maximum reasoning depth."
            _history.add(AgentHistory.SystemEvent(msg))
            _status = AgentStatus.Error(msg)
            return msg
        }

        if (_history.size >= config.maxHistorySize * 0.8) {
            summarizeHistory(maxItems = config.maxHistorySize)
        }

        _status = AgentStatus.Thinking

        val capabilities = (config.tools.values + config.actions.values).toList()

        val response = config.llmClient.sendMessage(
            agentId               = config.agentId,
            systemPrompt          = config.systemPrompt,
            history               = _history.takeLast(config.maxHistorySize),
            availableCapabilities = capabilities
        )

        if (response.functionCalls.isNotEmpty()) {
            _status = AgentStatus.Working(
                response.functionCalls.joinToString(", ") { it.functionName }
            )
            response.functionCalls.forEach { call ->
                _history.add(AgentHistory.ToolCallRequest(call.functionName, call.arguments, call.thoughtSignature))
            }
            val results = response.functionCalls.map { call -> executeCapabilitySafely(call) }
            results.forEach { _history.add(it) }
            return runReactLoop(depth + 1)
        }

        val finalText = response.textReply
            ?: "Error: LLM returned neither text nor a function call."

        _history.add(AgentHistory.AiResponse(text = finalText))
        _status = AgentStatus.Idle
        return finalText
    }

    private suspend fun executeCapabilitySafely(call: FunctionCall): AgentHistory.ToolExecutionResult {
        val tool   = config.tools[call.functionName]
        val action = config.actions[call.functionName]

        return try {
            val resultText = when {
                tool   != null -> tool.execute(call.arguments)
                action != null -> action.perform(config.agentId, call.arguments)
                else           -> "Error: capability '${call.functionName}' is not registered."
            }
            AgentHistory.ToolExecutionResult(
                toolName   = call.functionName,
                resultData = resultText,
                isSuccess  = tool != null || action != null
            )
        } catch (e: Exception) {
            val errMsg = "Execution error in '${call.functionName}': ${e.message}"
            AgentHistory.ToolExecutionResult(
                toolName   = call.functionName,
                resultData = errMsg,
                isSuccess  = false
            )
        }
    }
}
