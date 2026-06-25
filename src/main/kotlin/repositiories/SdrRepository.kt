package org.example.repositiories

import org.example.sdr.Event
import org.example.sdr.Lead

/**
 * SdrRepository — pure data layer. Single source of truth.
 *
 * Depends on: NOTHING (only plain data classes).
 *
 * Responsibilities:
 *  - Store and retrieve [Lead] objects by email.
 *  - Append [Event]s to both the owning lead's event list and the global [_systemEvents] log.
 *
 * No LLM calls, no orchestrator references, no business rules, no prints.
 */
class SdrRepository {

    private val _leads        = mutableMapOf<String, Lead>()
    private val _systemEvents = mutableListOf<Event>()

    // ── Leads ─────────────────────────────────────────────────────────────────

    fun save(lead: Lead)              { _leads[lead.email] = lead }
    fun getLead(email: String): Lead? = _leads[email]
    fun getLeads(): List<Lead>        = _leads.values.toList()

    // ── Events ────────────────────────────────────────────────────────────────

    /** Appends [event] to the lead's own list AND to the global log. The lead is identified by [Event.leadEmail]. */
    fun logEvent(event: Event) {
        _leads[event.leadEmail]?.events?.add(event)
        _systemEvents.add(event)
    }

    fun getEvents(): List<Event> = _systemEvents.toList()
}
