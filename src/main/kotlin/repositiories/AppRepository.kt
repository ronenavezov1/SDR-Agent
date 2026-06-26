package org.example.repositiories

import org.example.llm.LlmClient
import org.example.mock.MockEmailRepository
import org.example.sdr.QualificationConfig

/**
 * AppRepository — application-level wiring point.
 *
 * Creates and connects all repositories. Nothing outside this class
 * needs to know how the pieces are constructed.
 */
class AppRepository(
    llmClients:          List<LlmClient>,
    qualificationConfig: QualificationConfig,
    emailRepository:     EmailRepository = MockEmailRepository()
) {
    val sdrRepository = SdrRepository(
        llmClients          = llmClients,
        emailRepository     = emailRepository,
        qualificationConfig = qualificationConfig
    )

    fun shutdown() = sdrRepository.shutdown()
}
