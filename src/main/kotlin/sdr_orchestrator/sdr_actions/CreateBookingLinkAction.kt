package org.example.sdr_orchestrator.sdr_actions

import org.example.agent.Action
import org.example.sdr.*
import org.example.sdr_orchestrator.OrchestratorContext

class CreateBookingLinkAction(private val ctx: OrchestratorContext) : Action {

    override val name = "createBookingLink"
    override val description =
        "Creates a calendar booking link for a FULLY QUALIFIED lead and marks them QUALIFIED. " +
        "Rejected if the lead has not met all qualification criteria."
    override val parameters = mapOf(
        "leadEmail" to "The email address of the fully qualified lead."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val lead = ctx.getLead() ?: return "Error: Lead not found."

        val issues = mutableListOf<String>()
        val teamSize = lead.teamSize
        if (teamSize == null || teamSize < ctx.config.minTeamSize)
            issues.add("team_size ${teamSize ?: "unknown"} < minimum ${ctx.config.minTeamSize}")
        if (ctx.config.requireUseCase && lead.useCase == null)
            issues.add("use_case not provided")
        if (ctx.config.requireCommercialIntent && lead.commercialIntent != true)
            issues.add("commercial_intent not confirmed")
        if (lead.status is LeadStatus.Escalated)
            issues.add("lead is in ESCALATED state — resolve escalation first")

        if (issues.isNotEmpty()) {
            val reason = issues.joinToString("; ")
            ctx.logEvent(Event.PolicyBlocked(
                leadEmail = ctx.leadEmail,
                policyName = "BOOKING_REQUIRES_QUALIFICATION",
                reason = reason
            ))
            return "POLICY_BLOCKED [BOOKING_REQUIRES_QUALIFICATION]: $reason."
        }

        val link = ctx.createBookingLink(lead.name, lead.company)
        lead.bookingLink = link
        lead.status = LeadStatus.Qualified
        ctx.saveLead(lead)

        ctx.logEvent(Event.BookingLinkCreated(leadEmail = ctx.leadEmail, bookingLink = link, reason = BookingLinkReason.AgentQualified))
        ctx.logEvent(Event.LeadQualified(
            leadEmail = ctx.leadEmail,
            useCase = lead.useCase,
            teamSize = lead.teamSize,
            commercialIntent = lead.commercialIntent
        ))

        return "✅ Lead QUALIFIED. Booking link: $link — include this in your closing email to ${lead.name}."
    }
}
