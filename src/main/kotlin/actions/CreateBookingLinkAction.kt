package org.example.actions

import org.example.agent.Action
import org.example.mock.MockBookingService
import org.example.repositiories.SdrRepository
import org.example.sdr.*

class CreateBookingLinkAction(
    private val repository: SdrRepository,
    private val bookingService: MockBookingService,
    private val config: QualificationConfig
) : Action {

    override val name = "createBookingLink"
    override val description =
        "Creates a calendar booking link for a FULLY QUALIFIED lead and marks them QUALIFIED. " +
        "Rejected if the lead has not met all qualification criteria."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the fully qualified lead."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadEmail = args["leadEmail"]?.toString()?.trim() ?: return "Error: 'leadEmail' required."
        val lead = repository.getLead(leadEmail) ?: return "Error: Lead '$leadEmail' not found."

        // ── POLICY: Booking requires full qualification ────────────────────────
        val issues = mutableListOf<String>()
        val teamSize = lead.teamSize
        if (teamSize == null || teamSize < config.minTeamSize)
            issues.add("team_size ${teamSize ?: "unknown"} < minimum ${config.minTeamSize}")
        if (config.requireUseCase && lead.useCase == null)
            issues.add("use_case not provided")
        if (config.requireCommercialIntent && lead.commercialIntent != true)
            issues.add("commercial_intent not confirmed")
        if (lead.status is LeadStatus.Escalated)
            issues.add("lead is in ESCALATED state — resolve escalation first")

        if (issues.isNotEmpty()) {
            val reason = issues.joinToString("; ")
            repository.logEvent(Event.PolicyBlocked(
                leadEmail  = leadEmail,
                policyName = "BOOKING_REQUIRES_QUALIFICATION",
                reason     = reason))
            return "POLICY_BLOCKED [BOOKING_REQUIRES_QUALIFICATION]: $reason."
        }

        val link = bookingService.createBookingLink(lead.name, lead.company)
        lead.bookingLink = link
        lead.status = LeadStatus.Qualified

        repository.logEvent(Event.BookingLinkCreated(leadEmail = leadEmail, bookingLink = link))
        repository.logEvent(Event.LeadQualified(
            leadEmail = leadEmail, useCase = lead.useCase, teamSize = lead.teamSize, commercialIntent = lead.commercialIntent))

        return "✅ Lead QUALIFIED. Booking link: $link — include this in your closing email to ${lead.name}."
    }
}

