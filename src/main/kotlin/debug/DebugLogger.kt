package org.example.debug

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Simple debug tracer controlled by the SDR_DEBUG environment variable.
 *
 * Enable:  export SDR_DEBUG=true
 * Disable: unset SDR_DEBUG  (or any value other than "true")
 *
 * Output goes to stdout so it interleaves with the normal CLI output — making
 * it easy to follow the exact data flow: prompt → LLM → tool calls → result.
 */
object DebugLogger {

    val enabled: Boolean = System.getenv("SDR_DEBUG")?.lowercase() == "true"

    private val fmt: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private fun ts() = fmt.format(Instant.now())

    // ── Section separators ────────────────────────────────────────────────────

    fun agentStart(agentId: String, input: String) {
        if (!enabled) return
        println()
        println("$CYAN╔══ 🤖 AGENT START [$agentId]  ${ts()} ══$RESET")
        println("$CYAN║  INPUT PROMPT:$RESET")
        input.lines().forEach { println("$CYAN║    $RESET$it") }
        println("$CYAN╚${"═".repeat(60)}$RESET")
    }

    fun agentDone(agentId: String, output: String) {
        if (!enabled) return
        println("$GREEN╔══ ✅ AGENT DONE  [$agentId]  ${ts()} ══$RESET")
        println("$GREEN║  OUTPUT:$RESET")
        output.lines().take(30).forEach { println("$GREEN║    $RESET$it") }
        if (output.lines().size > 30) println("$GREEN║    … (${output.lines().size - 30} more lines)$RESET")
        println("$GREEN╚${"═".repeat(60)}$RESET")
        println()
    }

    // ── LLM interaction ───────────────────────────────────────────────────────

    fun llmRequest(agentId: String, historySize: Int, capabilityNames: List<String>) {
        if (!enabled) return
        println("$YELLOW  ┌─ 🧠 LLM REQUEST  [$agentId]  history=$historySize  tools=${capabilityNames}$RESET")
    }

    fun llmResponse(agentId: String, textPreview: String?, functionCalls: List<String>) {
        if (!enabled) return
        if (functionCalls.isNotEmpty()) {
            println("$YELLOW  │  ◀ LLM → function_calls=$functionCalls$RESET")
        } else {
            val preview = textPreview?.take(120)?.replace("\n", " ") ?: "(null)"
            println("$YELLOW  └─ ◀ LLM → text=\"$preview\"$RESET")
        }
    }

    // ── Tool / Action execution ───────────────────────────────────────────────

    fun toolCall(agentId: String, toolName: String, args: Map<String, Any>) {
        if (!enabled) return
        println("$BLUE  │  🔧 CALL  $toolName  args=$args$RESET")
    }

    fun toolResult(agentId: String, toolName: String, result: String, isSuccess: Boolean) {
        if (!enabled) return
        val icon = if (isSuccess) "✅" else "❌"
        val preview = result.take(200).replace("\n", " ")
        println("$BLUE  │  $icon RESULT [$toolName]: $preview$RESET")
    }

    // ── ReAct loop depth ──────────────────────────────────────────────────────

    fun reactLoop(agentId: String, depth: Int) {
        if (!enabled) return
        println("$DIM  │  ↻ ReAct depth=$depth$RESET")
    }

    // ── ANSI colour codes ─────────────────────────────────────────────────────

    private const val RESET  = "\u001B[0m"
    private const val CYAN   = "\u001B[36m"
    private const val GREEN  = "\u001B[32m"
    private const val YELLOW = "\u001B[33m"
    private const val BLUE   = "\u001B[34m"
    private const val DIM    = "\u001B[2m"
}
