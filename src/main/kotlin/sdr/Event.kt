package org.example.sdr

sealed interface Event {
    val leadEmail: String
    val timestamp: Long

    data class LeadReceived(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : Event

    data class EmailSent(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val subject: String,
        val body: String
    ) : Event

    data class ReplyReceived(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val body: String
    ) : Event

    data class QualificationUpdated(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis(),
        var useCase: String? = null,
        var teamSize: Int? = null,
        var commercialIntent: Boolean? = null
    ) : Event

    data class LeadQualified(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis(),
        var useCase: String? = null,
        var teamSize: Int? = null,
        var commercialIntent: Boolean? = null
    ) : Event

    data class LeadDisqualified(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val reason: String
    ) : Event

    data class BookingLinkCreated(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val bookingLink: String
    ) : Event

    data class HumanEscalationTriggered(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val reason: String,
        val triggerMessage: String
    ) : Event

    data class HumanEscalationResolved(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val humanResponse: String
    ) : Event

    data class PolicyBlocked(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val policyName: String,
        val reason: String
    ) : Event

    data class AgentDecision(
        override val leadEmail: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val decision: String,
        val reason: String
    ) : Event
}
