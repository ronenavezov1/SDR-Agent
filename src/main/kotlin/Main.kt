package org.example

import kotlinx.coroutines.runBlocking
import org.example.llm.GeminiLlmClient
import org.example.orchestrator.WeatherOrchestrator
import org.example.view.WeatherConsoleView

/**
 * Application entry point.
 *
 * Wires together the three top-level components:
 *  1. [GeminiLlmClient]     — the AI model backend (Google Gemini).
 *  2. [WeatherOrchestrator] — creates and coordinates the agent pipeline.
 *  3. [WeatherConsoleView]  — reads stdin, prints responses.
 *
 * To swap the LLM provider, replace [GeminiLlmClient] with any other
 * [LlmClient] implementation (e.g. [OpenAiLlmClient]) — nothing else changes.
 *
 * API key resolution order: GOOGLE_API_KEY (recommended) → GEMINI_API_KEY (legacy).
 */
fun main() = runBlocking {
    val apiKey = System.getenv("GOOGLE_API_KEY")
        ?: System.getenv("GEMINI_API_KEY")
        ?: throw IllegalArgumentException(
            "CRITICAL: Set the GOOGLE_API_KEY environment variable before running."
        )

    val llmClient    = GeminiLlmClient(apiKey = apiKey, modelName = "gemini-2.5-flash")
    val orchestrator = WeatherOrchestrator(llmClient = llmClient)
    val view         = WeatherConsoleView(orchestrator = orchestrator)

    view.startChatLoop()
}
