package org.example.actions

import org.example.agent.Action
import org.example.mock.MockBookingService
import org.example.sdr.*

/**
 * Action: creates a booking link for a qualified lead.
 *
 * POLICY enforced: BOOKING_REQUIRES_QUALIFICATION — all three qualification
 * criteria must be met. The check is in code; the LLM cannot bypass it.
 * If the criteria are not met the tool returns POLICY_BLOCKED and the LLM
 * must handle that outcome (e.g. send a follow-up instead).
 */
class CreateBookingLinkAction(
    private val store: LeadStore,
    private val bookingService: MockBookingService,
    private val config: QualificationConfig,
    private val auditLog: AuditLog
) : Action {

    override val name = "createBookingLink"
    override val description =
        "Creates a calendar booking link for a FULLY QUALIFIED lead and marks them QUALIFIED. " +
        "Will be rejected if the lead has not yet met all qualification criteria."
    override val parameters = mapOf(
        "leadId" to "The ID of the fully qualified lead."
    )

    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val leadId = args["leadId"]?.toString()?.trim() ?: return "Error: 'leadId' required."
        val lead = store.get(leadId) ?: return "Error: Lead '$leadId' not found."

        // ── POLICY: Booking requires full qualification ────────────────────────
        val issues = mutableListOf<String>()
        val teamSize = lead.qualificationData.teamSize
        if (teamSize == null || teamSize < config.minTeamSize)
            issues.add("team_size ${teamSize ?: "unknown"} < minimum ${config.minTeamSize}")
        if (config.requireUseCase && lead.qualificationData.useCase == null)
            issues.add("use_case not provided")
        if (config.requireCommercialIntent && lead.qualificationData.commercialIntent != true)
            issues.add("commercial_intent not confirmed")
        if (lead.status == LeadStatus.ESCALATED_HUMAN)
            issues.add("lead is in ESCALATED_HUMAN state — resolve escalation first")

        if (issues.isNotEmpty()) {
            val msg = "POLICY_BLOCKED [BOOKING_REQUIRES_QUALIFICATION]: ${issues.joinToString("; ")}."
            auditLog.log(AuditEvent(AuditEventType.POLICY_BLOCKED, leadId,
                mapOf("policy" to "BOOKING_REQUIRES_QUALIFICATION", "issues" to issues.toString())))
            return msg
        }

        val link = bookingService.createBookingLink(lead.name, lead.company)
        lead.bookingLink = link
        lead.status = LeadStatus.QUALIFIED

        auditLog.log(AuditEvent(AuditEventType.BOOKING_LINK_CREATED, leadId, mapOf("link" to link)))
        auditLog.log(AuditEvent(AuditEventType.LEAD_QUALIFIED, leadId,
            mapOf("name" to lead.name, "company" to lead.company, "link" to link)))

        return "✅ Lead QUALIFIED. Booking link: $link — include this in your closing email to ${lead.name}."
    }
}
