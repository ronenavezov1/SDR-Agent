package org.example.debug

import org.example.agent.AgentHistory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Debug tracer controlled by the SDR_DEBUG environment variable.
 *
 * Enable:  export SDR_DEBUG=true
 * Disable: unset SDR_DEBUG
 *
 * Each agent call produces exactly two log blocks:
 *   1. AGENT START — the full input prompt
 *   2. AGENT DONE  — the full conversation history + final output
 *
 * No intermediate ping-pong (LLM request/response, tool calls, react loops) is printed.
 */
object DebugLogger {

    val enabled: Boolean = System.getenv("SDR_DEBUG")?.lowercase() == "true"

    private val fmt: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private fun ts() = fmt.format(Instant.now())

    private fun bar(char: Char = '═', width: Int = 80) = char.toString().repeat(width)

    // ── Agent lifecycle ───────────────────────────────────────────────────────

    fun agentStart(agentId: String, workOnId: String, llmName: String, input: String) {
        if (!enabled) return
        println()
        println("$CYAN${bar()}$RESET")
        println("$CYAN  🤖  AGENT START   agent=$agentId   lead=$workOnId   llm=$llmName   ${ts()}$RESET")
        println("$CYAN${bar('─')}$RESET")
        println("$CYAN  INPUT:$RESET")
        input.lines().forEach { println("$CYAN  │  $RESET$it") }
        println("$CYAN${bar()}$RESET")
    }

    fun agentDone(
        agentId: String,
        workOnId: String,
        llmName: String,
        history: List<AgentHistory>,
        output: String
    ) {
        if (!enabled) return
        println()
        println("$GREEN${bar()}$RESET")
        println("$GREEN  ✅  AGENT DONE    agent=$agentId   lead=$workOnId   llm=$llmName   ${ts()}$RESET")
        println("$GREEN${bar('─')}$RESET")
        println("$GREEN  HISTORY (${history.size} turns):$RESET")
        history.forEachIndexed { i, item ->
            val (label, text) = when (item) {
                is AgentHistory.UserInput          -> "USER  " to item.text
                is AgentHistory.AiResponse         -> "AI    " to item.text
                is AgentHistory.ToolCallRequest    -> "CALL  " to "${item.toolName}  args=${item.arguments}"
                is AgentHistory.ToolExecutionResult -> {
                    val icon = if (item.isSuccess) "RESULT" else "ERROR "
                    icon to "[${item.toolName}] ${item.resultData}"
                }
                is AgentHistory.SystemEvent        -> "SYS   " to item.eventDescription
                is AgentHistory.Summary            -> "SUMMARY" to item.text
            }
            println("$GREEN  │  [${"${i + 1}".padStart(2)}] $label  $RESET${text.replace("\n", "\n$GREEN  │          $RESET")}")
        }
        println("$GREEN${bar('─')}$RESET")
        println("$GREEN  OUTPUT:$RESET")
        output.lines().forEach { println("$GREEN  │  $RESET$it") }
        println("$GREEN${bar()}$RESET")
        println()
    }

    // ── History compression ───────────────────────────────────────────────────

    fun historySummarize(agentId: String, historySize: Int) {
        if (!enabled) return
        println("$DIM  🗜️  [$agentId]  Compressing history ($historySize turns)…$RESET")
    }

    fun historySummarizeError(agentId: String, e: Exception) {
        if (!enabled) return
        println("$RED  ❌  [$agentId]  History compression failed: ${e::class.simpleName}: ${e.message?.take(120)}$RESET")
    }

    // ── LLM network layer ─────────────────────────────────────────────────────

    fun llmRetry(providerName: String, errorMsg: String, delayMs: Long, attempt: Int, maxRetries: Int) {
        if (!enabled) return
        println("$YELLOW  ⚠️  [$providerName]  Transient error — retrying in ${delayMs}ms (attempt $attempt/$maxRetries): ${errorMsg.take(80)}$RESET")
    }

    fun llmClientFailure(clientName: String, errorMsg: String?) {
        if (!enabled) return
        println("$YELLOW  ⚠️  [tryWithAllClients]  $clientName failed: ${errorMsg?.take(120)}$RESET")
    }

    fun llmSendFailure(providerName: String, agentId: String, errorMsg: String) {
        if (!enabled) return
        println("$YELLOW  ❌  [$providerName]  Agent '$agentId' failed: ${errorMsg.take(200)}$RESET")
    }

    /** Always visible — non-LLM exceptions indicate code bugs, not LLM failures. */
    fun orchestrationBug(leadEmail: String, e: Exception) {
        println("$RED🔴 [tryWithAllClients] Unexpected exception (${e::class.simpleName}) for $leadEmail: ${e.message}$RESET")
    }

    // ── ANSI colour codes ─────────────────────────────────────────────────────

    private const val RESET  = "\u001B[0m"
    private const val CYAN   = "\u001B[36m"
    private const val GREEN  = "\u001B[32m"
    private const val YELLOW = "\u001B[33m"
    private const val RED    = "\u001B[31m"
    private const val DIM    = "\u001B[2m"
}
