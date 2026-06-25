package org.example.sdr

import java.util.UUID

enum class EmailDirection { OUTBOUND, INBOUND }

data class EmailMessage(
    val direction: EmailDirection,
    val subject: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Central state object for a single inbound lead.
 *
 * All mutations go through tools (enforced by the orchestrator). Direct mutation
 * from outside the tools package is a design violation — the tools are the only
 * write path, making every state change observable via the audit log.
 */
data class Lead(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val email: String,
    val company: String,
    val inboundMessage: String,
    var status: LeadStatus = LeadStatus.NEW,
    var qualificationData: QualificationData = QualificationData(),
    val emailThread: MutableList<EmailMessage> = mutableListOf(),
    var followUpCount: Int = 0,
    var outreachSent: Boolean = false,
    var escalation: HumanEscalation? = null,
    var bookingLink: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Compact context string sent to the agent so it understands the current lead state. */
    fun toContextString(): String = buildString {
        appendLine("=== Lead State ===")
        appendLine("ID: $id | Name: $name | Company: $company | Email: $email")
        appendLine("Status: $status | Outreach sent: $outreachSent | Follow-ups sent: $followUpCount")
        appendLine("Qualification:")
        appendLine("  use_case:          ${qualificationData.useCase ?: "NOT YET KNOWN"}")
        appendLine("  team_size:         ${qualificationData.teamSize ?: "NOT YET KNOWN"}")
        appendLine("  commercial_intent: ${qualificationData.commercialIntent ?: "NOT YET KNOWN"}")
        if (escalation != null) {
            appendLine("Escalation: ${escalation!!.reason}")
            appendLine("  Human response: ${escalation!!.humanResponse ?: "PENDING"}")
        }
        if (bookingLink != null) appendLine("Booking link: $bookingLink")
        appendLine("=================")
    }
}
