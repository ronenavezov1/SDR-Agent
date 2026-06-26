package org.example.sdr

data class HumanEscalation(
    /** Why this was escalated (e.g. "Lead asked about pricing"). */
    val reason: String,
    /** The exact message excerpt that triggered escalation. */
    val triggerMessage: String = "",
    /** Set by the human reviewer once they respond. Null means still pending. */
    var humanResponse: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
