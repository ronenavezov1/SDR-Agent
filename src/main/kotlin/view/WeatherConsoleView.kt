package org.example.view

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.orchestrator.WeatherOrchestrator

/**
 * Console-based UI for the weather chat assistant.
 *
 * ## Responsibilities
 * - Read user input from stdin.
 * - Pass each message to [WeatherOrchestrator] and print the response.
 * - Handle the "clear" and "exit"/"quit" commands.
 *
 * ## Why this is simple (KISS)
 * [WeatherOrchestrator.processQuery] is a suspend function that calls [onResponse]
 * when the answer is ready.  The view just suspends while waiting — no StateFlow,
 * no polling, no timeout mechanism, no race conditions possible.
 *
 * @param orchestrator The orchestrator that manages all AI agents.
 */
class WeatherConsoleView(private val orchestrator: WeatherOrchestrator) {

    /**
     * Starts the interactive chat loop.
     *
     * Suspends (does not block a thread) while waiting for the user to type
     * or while the orchestrator processes a query.
     * Exits when the user types "exit" / "quit" or stdin is closed.
     */
    suspend fun startChatLoop() {
        printWelcome()

        while (true) {
            print("You: ")
            System.out.flush()

            // IO-bound readline: offloaded to the IO dispatcher
            val input = withContext(Dispatchers.IO) { readlnOrNull() }?.trim() ?: break

            when {
                input.equals("exit", ignoreCase = true) ||
                input.equals("quit", ignoreCase = true) -> {
                    println("\nGoodbye! 👋")
                    break
                }
                input.equals("clear", ignoreCase = true) -> {
                    orchestrator.clearHistory()
                    println("🗑️  History cleared.\n")
                    continue
                }
                input.isBlank() -> continue
            }

            // Show a thinking indicator; the coroutine suspends here until
            // the orchestrator delivers a result via the onResponse callback.
            print("🤔 Thinking...")
            System.out.flush()

            println("🌤  Agent: ${orchestrator.processQuery(input)}\n")
        }
    }

    private fun printWelcome() {
        println("""
            ╔══════════════════════════════════════════╗
            ║       🌤  Weather Agent Chat  🌤         ║
            ╠══════════════════════════════════════════╣
            ║  Ask any weather-related question!       ║
            ║  Commands:                               ║
            ║    clear      — clear conversation       ║
            ║    exit/quit  — stop the chat            ║
            ╚══════════════════════════════════════════╝
        """.trimIndent())
        println()
    }
}
