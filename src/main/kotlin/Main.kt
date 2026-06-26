package org.example

import kotlinx.coroutines.runBlocking
import org.example.llm.GeminiLlmClient
import org.example.repositiories.AppRepository
import org.example.sdr.QualificationConfig
import org.example.sdr_view.SdrConsoleView
import org.example.sdr_viewmodel.SdrViewModel

fun main() = runBlocking {
    val apiKeys = (System.getenv("GOOGLE_API_KEY") ?: System.getenv("GEMINI_API_KEY"))
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: throw IllegalArgumentException(
            "Set the GOOGLE_API_KEY environment variable before running. " +
            "Multiple keys can be provided comma-separated: key1,key2,key3"
        )

    val app  = AppRepository(
        llmClients          = apiKeys.map { GeminiLlmClient(apiKey = it, modelName = "gemini-2.5-flash") },
        qualificationConfig = QualificationConfig(minTeamSize = 10, maxFollowUps = 3)
    )
    val view = SdrConsoleView(viewModel = SdrViewModel(app))

    view.start()
    app.shutdown()
}
