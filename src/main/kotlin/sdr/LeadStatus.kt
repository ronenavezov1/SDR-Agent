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
    data object Disqualified : LeadStatus

    /** All criteria met, booking link sent — terminal. */
    data object Qualified : LeadStatus

    /** Waiting for a human reviewer before the agent can continue. */
    data class Escalated(val escalation: HumanEscalation) : LeadStatus
}

