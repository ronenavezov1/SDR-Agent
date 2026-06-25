package org.example

import kotlinx.coroutines.runBlocking
import org.example.llm.GeminiLlmClient
import org.example.orchestrator.SdrOrchestrator
import org.example.sdr.QualificationConfig
import org.example.view.SdrConsoleView

/**
 * Application entry point for the AI SDR Agent.
 *
 * Wires together:
 *  1. [GeminiLlmClient]  — the AI model backend (Google Gemini 2.5 Flash).
 *  2. [SdrOrchestrator]  — lead lifecycle management, policies, human escalation.
 *  3. [SdrConsoleView]   — interactive CLI (new-lead / reply / resolve / demo …).
 *
 * To swap the LLM provider, replace [GeminiLlmClient] with any [LlmClient] impl.
 * To change qualification rules, edit [QualificationConfig] here — nothing else changes.
 *
 * API key: GOOGLE_API_KEY env var (or GEMINI_API_KEY as fallback).
 */
fun main() = runBlocking {
    val apiKey = System.getenv("GOOGLE_API_KEY")
        ?: System.getenv("GEMINI_API_KEY")
        ?: throw IllegalArgumentException(
            "Set the GOOGLE_API_KEY environment variable before running."
        )

    val llmClient    = GeminiLlmClient(apiKey = apiKey, modelName = "gemini-2.5-flash")
    val orchestrator = SdrOrchestrator(
        llmClient = llmClient,
        config    = QualificationConfig(minTeamSize = 10, maxFollowUps = 3)
    )
    val view = SdrConsoleView(orchestrator = orchestrator)

    view.start()
}
