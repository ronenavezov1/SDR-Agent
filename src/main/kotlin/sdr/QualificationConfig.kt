package org.example.sdr

/**
 * Configurable qualification rules for the SDR agent.
 * Change these values to adjust qualification thresholds without touching agent logic.
 */
data class QualificationConfig(
    /** Team/company size must be at least this to qualify. */
    val minTeamSize: Int = 10,
    /** A described use case / problem is required to qualify. */
    val requireUseCase: Boolean = true,
    /** The lead must express active commercial intent (buying/evaluating). */
    val requireCommercialIntent: Boolean = true,
    /** Maximum follow-up emails before giving up. */
    val maxFollowUps: Int = 3,
    /**
     * Maximum times an email draft can be rejected and rewritten before the best attempt is sent.
     * Exists to prevent an infinite writer↔reviewer loop while still allowing meaningful revision.
     */
    val maxEmailDraftRetries: Int = 3,
    /** Keywords that trigger mandatory human escalation (pricing/discount discussions). */
    val pricingKeywords: List<String> = listOf(
        "price", "pricing", "cost", "discount", "cheap", "expensive",
        "budget", "how much", "fee", "payment", "plan", "tier"
    )
)
