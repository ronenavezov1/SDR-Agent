package org.example.sdr

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AuditEventType {
    LEAD_RECEIVED,
    EMAIL_SENT,
    REPLY_RECEIVED,
    QUALIFICATION_UPDATED,
    LEAD_QUALIFIED,
    LEAD_DISQUALIFIED,
    BOOKING_LINK_CREATED,
    HUMAN_ESCALATION_TRIGGERED,
    HUMAN_ESCALATION_RESOLVED,
    POLICY_BLOCKED,
    AGENT_DECISION
}

data class AuditEvent(
    val type: AuditEventType,
    val leadId: String,
    val details: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Append-only event log providing full observability into the agent's behaviour.
 *
 * Every meaningful state change (email sent, qualification updated, policy violation,
 * human escalation) is logged here. Nothing is deleted or overwritten — the log is
 * the definitive record of what happened and why.
 */
class AuditLog {
    private val _events = mutableListOf<AuditEvent>()

    val events: List<AuditEvent> get() = _events.toList()

    fun log(event: AuditEvent) {
        _events.add(event)
        val time = formatter.format(Instant.ofEpochMilli(event.timestamp))
        val icon = iconFor(event.type)
        println("$icon [AUDIT $time] [lead:${event.leadId}] ${event.type}  ${event.details}")
    }

    fun getEventsForLead(leadId: String): List<AuditEvent> =
        _events.filter { it.leadId == leadId }

    fun printTimeline(leadId: String) {
        val evs = getEventsForLead(leadId)
        println("\n══════════════════════════════════════════════════")
        println("  Timeline for Lead: $leadId  (${evs.size} events)")
        println("══════════════════════════════════════════════════")
        if (evs.isEmpty()) { println("  No events found."); return }
        evs.forEach { e ->
            val time = formatter.format(Instant.ofEpochMilli(e.timestamp))
            val icon = iconFor(e.type)
            println("  $time  $icon  ${e.type.name.padEnd(32)} ${e.details}")
        }
        println("══════════════════════════════════════════════════\n")
    }

    fun printAllTimelines() {
        val leadIds = _events.map { it.leadId }.distinct()
        if (leadIds.isEmpty()) { println("Audit log is empty."); return }
        leadIds.forEach { printTimeline(it) }
    }

    private fun iconFor(type: AuditEventType) = when (type) {
        AuditEventType.LEAD_RECEIVED               -> "🆕"
        AuditEventType.EMAIL_SENT                  -> "📧"
        AuditEventType.REPLY_RECEIVED              -> "📨"
        AuditEventType.QUALIFICATION_UPDATED       -> "🔍"
        AuditEventType.LEAD_QUALIFIED              -> "✅"
        AuditEventType.LEAD_DISQUALIFIED           -> "❌"
        AuditEventType.BOOKING_LINK_CREATED        -> "📅"
        AuditEventType.HUMAN_ESCALATION_TRIGGERED  -> "🚨"
        AuditEventType.HUMAN_ESCALATION_RESOLVED   -> "👤"
        AuditEventType.POLICY_BLOCKED              -> "🛡️"
        AuditEventType.AGENT_DECISION              -> "🤖"
    }

    private val formatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
}
