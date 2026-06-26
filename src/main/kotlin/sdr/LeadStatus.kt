package org.example.sdr

/**
 * Sealed interface — exhaustive when-expressions always enforced by the compiler.
 *
 * [Escalated] carries the live escalation data so the status and its context
 * are always co-located; no separate nullable field needed on [Lead].
 */
sealed interface LeadStatus {
    /** Lead arrived, no outreach sent yet. */
    data object New : LeadStatus

    /** Outreach or follow-up sent — waiting for the lead to reply. */
    data object AwaitingClientResponse : LeadStatus

    /** Does not meet qualification criteria — terminal. */
    data class Disqualified(val reason: String) : LeadStatus

    /**
     * All qualification fields present and agent decided to qualify — terminal.
     * Booking link type: standard agent-qualified link.
     */
    data object Qualified : LeadStatus

    /** Waiting for a human reviewer before the agent can continue. */
    data class Escalated(val escalation: HumanEscalation) : LeadStatus

    /**
     * Lead explicitly requested to speak with a human — terminal.
     * Booking link type: client-ask link (dedicated channel for self-referred leads).
     */
    data class ApprovedClientAsk(val bookingLink: String) : LeadStatus

    /**
     * Sales team instructed to pass the lead forward, but qualification data is incomplete — terminal.
     * Booking link type: sales-team-ask link (internal handoff, not agent-qualified).
     */
    data class ApprovedSalesTeamAsk(val bookingLink: String) : LeadStatus

    /**
     * All LLM clients failed — no agent decision was possible — terminal.
     * Booking link type: fallback link (separate from all agent-driven outcomes).
     */
    data class ApprovedLlmFailed(val fallbackBookingLink: String) : LeadStatus
}

