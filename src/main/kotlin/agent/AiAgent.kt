package org.example.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.example.llm.FunctionCall
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
     * Processes a user message through the full ReAct loop and notifies
     * the caller via [onComplete] when a final answer is ready.
     *
     * If the agent is currently busy ([AgentStatus] is not [AgentStatus.Idle]),
     * the call is rejected immediately: [onComplete] is invoked with a message
     * that describes the current status and includes a formatted snapshot of the
     * recent history window — so the caller knows exactly what the agent is doing
     * and why it cannot accept new input right now.
     *
     * The entire loop is guarded by [AgentConfig.timeoutMs]. If the LLM (or any
     * tool) takes too long the coroutine is cancelled, the agent status is reset
     * to [AgentStatus.Idle], and [onComplete] is called with an error message.
     *
     * @param input      The user's message as a plain string.
     * @param onComplete Suspend callback invoked with the agent's final reply.
     */
    suspend fun processInput(input: String) : String{
        // ── Busy guard ────────────────────────────────────────────────────────
        // Only active states (Thinking / Working) block new input.
        // Error is a terminal state from a *previous* request — the agent is no
        // longer doing anything, so we reset it to Idle and let the new input
        // through (giving the user a chance to recover without calling clearHistory).
        if (_status is AgentStatus.Error) {
            println("⚠️ [${config.agentId}] Recovering from error state — resetting to Idle.")
            _status = AgentStatus.Idle
        }

        if (_status is AgentStatus.Thinking || _status is AgentStatus.Working) {
            val statusDesc = when (val s = _status) {
                is AgentStatus.Thinking -> "thinking"
                is AgentStatus.Working  -> "working on: ${s.toolName}"
                else                    -> "busy" // unreachable
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

        _history.add(AgentHistory.UserInput(text = input))
        val result = try {
            withTimeout(config.timeoutMs.milliseconds) {
                runReactLoop(depth = 0)
            }
        } catch (e: Exception) {
            val msg = "⏱️ [${config.agentId}] Request timed out or failed: ${e.message}"
            println(msg)
            _status = AgentStatus.Idle
            msg
        }
        return result
    }

    /**
     * Resets the conversation history and returns the agent to [AgentStatus.Idle].
     * Call this to start a fresh session without creating a new agent instance.
     */
    fun clearHistory() {
        _history.clear()
        _status = AgentStatus.Idle
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
     *                   Older items are dropped before summarising to stay within the
     *                   LLM's context window. Defaults to 30.
     * @param timeoutMs  Maximum time in milliseconds allowed for the LLM call.
     *                   Defaults to 30 000 ms (30 seconds).
     * @return           The summary text, or null if the operation timed out or failed.
     */
    suspend fun summarizeHistory(
        maxItems:  Int  = 30,
        timeoutMs: Long = 30_000L
    ): String? {
        if (_history.isEmpty()) return null

        println("\n🗜️  [${config.agentId}] Starting history compression " +
                "(${_history.size} items → summary)…")

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
        return try {
            val summaryText = withTimeout(timeoutMs.milliseconds) {
                val response = config.llmClient.sendMessage(
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

            println("✅ [${config.agentId}] History compressed to 1 summary entry.")
            summaryText

        } catch (e: Exception) {
            // Timeout or any LLM error — leave history untouched (fail-safe)
            println("⚠️  [${config.agentId}] Summarisation failed: ${e.message}. History unchanged.")
            null
        }
    }

    // ── ReAct loop ────────────────────────────────────────────────────────────

    /**
     * The ReAct (Reason + Act) loop — the core reasoning engine.
     *
     * Each iteration:
     *  1. Sends the current history to the LLM.
     *  2a. If the LLM requests tool calls → executes all tools **concurrently**
     *      (parallel, not serial), appends results to history, recurses (depth + 1).
     *  2b. If the LLM returns text → appends to history, returns the text.
     *
     * Concurrent tool execution uses [async]/[await], so a request that triggers
     * two independent tools is handled in parallel — saving wall-clock time.
     *
     * @param depth Recursion counter. Hard-stops at depth > 5 to prevent infinite loops.
     * @return The agent's final plain-text reply to the user.
     */
    private suspend fun runReactLoop(depth: Int): String {

        // ── Infinite-loop guard ───────────────────────────────────────────────
        if (depth > config.maxDepth) {
            val msg = "[${config.agentId}] Aborted: reached maximum reasoning depth."
            _history.add(AgentHistory.SystemEvent(msg))
            _status = AgentStatus.Error(msg)
            return msg
        }

        _status = AgentStatus.Thinking

        // Merge tools + actions into one list — the LLM adapter converts them
        // into a JSON FunctionDeclaration array sent along with the request.
        val capabilities = (config.tools.values + config.actions.values).toList()

        // ── Ask the LLM what to do next ───────────────────────────────────────
        val response = config.llmClient.sendMessage(
            systemPrompt          = config.systemPrompt,
            history               = _history.takeLast(config.maxHistorySize),
            availableCapabilities = capabilities
        )

        println("▶ [${config.agentId}] LLM → " +
                "text=\"${response.textReply?.take(80)}\", " +
                "funcCalls=${response.functionCalls.map { it.functionName }}")

        // ── Scenario A: The LLM wants to call one or more capabilities ────────
        if (response.functionCalls.isNotEmpty()) {

            _status = AgentStatus.Working(
                response.functionCalls.joinToString(", ") { it.functionName }
            )

            // 1. Record every function-call request the LLM made
            response.functionCalls.forEach { call ->
                _history.add(AgentHistory.ToolCallRequest(call.functionName, call.arguments))
            }

            // 2. Execute all tool/action calls sequentially
            val results = response.functionCalls.map { call -> executeCapabilitySafely(call) }

            // 3. Record all results before looping back to the LLM
            results.forEach { _history.add(it) }

            // 4. Recurse: give the LLM the results so it can continue reasoning
            return runReactLoop(depth + 1)
        }

        // ── Scenario B: The LLM returned a final text answer ─────────────────
        val finalText = response.textReply
            ?: "Error: LLM returned neither text nor a function call."

        _history.add(AgentHistory.AiResponse(text = finalText))
        _status = AgentStatus.Idle
        return finalText
    }

    /**
     * Executes a single capability (Tool or Action) and wraps it in try-catch.
     *
     * If the tool throws, the exception is converted into an error string that
     * is fed back to the LLM.  This lets the LLM recover gracefully (e.g. retry
     * with different arguments) instead of crashing the whole agent.
     *
     * @param call The function call the LLM requested (name + arguments).
     */
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
            AgentHistory.ToolExecutionResult(
                toolName   = call.functionName,
                resultData = "Execution error in '${call.functionName}': ${e.message}",
                isSuccess  = false
            )
        }
    }
}
