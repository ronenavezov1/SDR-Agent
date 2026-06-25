package org.example.sdr

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
 * [events] is the append-only history of everything that happened to this lead.
 * [status] is the current state; [LeadStatus.Escalated] embeds the escalation
 * data directly so there is no separate nullable field that can drift out of sync.
 */
data class Lead(
    val name: String,
    val email: String,
    val company: String,
    val inboundMessage: String,
    var status: LeadStatus = LeadStatus.New,
    var useCase: String? = null,
    var teamSize: Int? = null,
    var commercialIntent: Boolean? = null,
    val emailThread: MutableList<EmailMessage> = mutableListOf(),
    var followUpCount: Int = 0,
    var outreachSent: Boolean = false,
    var bookingLink: String? = null,
    val events: MutableList<Event> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Full context snapshot sent to agents on each turn.
     * Includes current state AND the complete event history so agents understand
     * the full chain of events: what was sent, what the lead said, what changed,
     * and any escalations — without relying on their own (cleared) conversation history.
     */
    fun toContextString(): String = buildString {
        appendLine("=== Lead State ===")
        appendLine("Name: $name | Company: $company | Email: $email")
        val statusLabel = when (val s = status) {
            is LeadStatus.New                    -> "NEW"
            is LeadStatus.AwaitingClientResponse -> "AWAITING_CLIENT_RESPONSE"
            is LeadStatus.Disqualified           -> "DISQUALIFIED"
            is LeadStatus.Qualified              -> "QUALIFIED"
            is LeadStatus.Escalated              -> "ESCALATED — ${s.escalation.reason}"
        }
        appendLine("Status: $statusLabel | Outreach sent: $outreachSent | Follow-ups: $followUpCount")
        appendLine("Qualification:")
        appendLine("  use_case:          ${useCase ?: "NOT YET KNOWN"}")
        appendLine("  team_size:         ${teamSize ?: "NOT YET KNOWN"}")
        appendLine("  commercial_intent: ${commercialIntent ?: "NOT YET KNOWN"}")
        if (status is LeadStatus.Escalated) {
            val esc = (status as LeadStatus.Escalated).escalation
            appendLine("Escalation pending: ${esc.reason}")
            appendLine("  Trigger: \"${esc.triggerMessage}\"")
            appendLine("  Human response: ${esc.humanResponse ?: "PENDING"}")
        }
        if (bookingLink != null) appendLine("Booking link: $bookingLink")

        appendLine("=== Event History (${events.size} events) ===")
        if (events.isEmpty()) {
            appendLine("  (none)")
        } else {
            events.forEach { e ->
                val label  = e::class.simpleName ?: "Event"
                val detail = eventDetail(e)
                appendLine("  [$label]  $detail")
            }
        }
        append("=================")
    }

    private fun eventDetail(e: Event): String = when (e) {
        is Event.LeadReceived             -> "Lead created. Initial message: \"${inboundMessage.take(120)}\""
        is Event.EmailSent                -> "Outgoing email — subject: \"${e.subject}\" | body: \"${e.body.take(200)}\""
        is Event.ReplyReceived            -> "Lead replied: \"${e.body.take(200)}\""
        is Event.QualificationUpdated     -> "Qualification updated — useCase=${e.useCase} teamSize=${e.teamSize} intent=${e.commercialIntent}"
        is Event.LeadQualified            -> "Lead QUALIFIED — useCase=${e.useCase} teamSize=${e.teamSize} intent=${e.commercialIntent}"
        is Event.LeadDisqualified         -> "Lead DISQUALIFIED — reason: \"${e.reason}\""
        is Event.BookingLinkCreated       -> "Booking link issued: ${e.bookingLink}"
        is Event.HumanEscalationTriggered -> "Escalated to human — reason: \"${e.reason}\" | trigger: \"${e.triggerMessage.take(120)}\""
        is Event.HumanEscalationResolved  -> "Escalation resolved — human said: \"${e.humanResponse.take(200)}\""
        is Event.PolicyBlocked            -> "POLICY BLOCKED [${e.policyName}]: ${e.reason}"
        is Event.AgentDecision            -> "Agent decision — ${e.decision}: ${e.reason.take(120)}"
    }
}

