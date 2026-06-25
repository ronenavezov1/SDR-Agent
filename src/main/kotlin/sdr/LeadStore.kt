package org.example.sdr

/**
 * Thread-safe (single-threaded coroutine context) in-memory store for leads.
 * The orchestrator owns this; tools receive a reference to it for reads and writes.
 */
class LeadStore {
    private val _leads = mutableMapOf<String, Lead>()

    fun save(lead: Lead) { _leads[lead.id] = lead }
    fun get(leadId: String): Lead? = _leads[leadId]
    fun all(): List<Lead> = _leads.values.toList()
    fun exists(leadId: String): Boolean = _leads.containsKey(leadId)
}
