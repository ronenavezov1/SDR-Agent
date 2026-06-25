package org.example.sdr

enum class LeadStatus {
    NEW,              // Lead just arrived, no outreach sent yet
    AWAITING_REPLY,   // Outreach or follow-up sent, waiting for lead to respond
    QUALIFYING,       // Reply received, extracting qualification data
    ESCALATED_HUMAN,  // Waiting for a human to review before proceeding
    QUALIFIED,        // All criteria met — terminal success state
    DISQUALIFIED      // Does not meet criteria — terminal failure state
}
