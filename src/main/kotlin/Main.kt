package org.example

import kotlinx.coroutines.runBlocking
import org.example.llm.GeminiLlmClient
import org.example.orchestrator.SdrOrchestrator
import org.example.repositiories.SdrRepository
import org.example.sdr.QualificationConfig
import org.example.view.SdrConsoleView
import org.example.viewmodel.SdrViewModel

fun main() = runBlocking {
    val apiKey = System.getenv("GOOGLE_API_KEY")
        ?: System.getenv("GEMINI_API_KEY")
        ?: throw IllegalArgumentException(
            "Set the GOOGLE_API_KEY environment variable before running."
        )

    // ── Dependency wiring (top-down, no cycles) ───────────────────────────────
    //
    //   SdrConsoleView
    //       └─ SdrViewModel
    //             ├─ SdrOrchestrator  ─── SdrRepository
    //             └─ SdrRepository        AiAgent / Actions
    //
    val config       = QualificationConfig(minTeamSize = 10, maxFollowUps = 3)
    val llmClient    = GeminiLlmClient(apiKey = apiKey, modelName = "gemini-2.5-flash")
    val repository   = SdrRepository()
    val orchestrator = SdrOrchestrator(llmClient = llmClient, repository = repository, config = config)
    val viewModel    = SdrViewModel(orchestrator = orchestrator, repository = repository)
    val view         = SdrConsoleView(viewModel = viewModel)

    view.start()
}
