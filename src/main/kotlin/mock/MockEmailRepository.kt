package org.example.mock

import org.example.repositiories.EmailRepository
import org.example.repositiories.ReceivedEmail
import org.example.repositiories.SentEmail
import java.util.UUID

class MockEmailRepository : EmailRepository {

    private val _outbox                = mutableListOf<SentEmail>()
    private val _inbox                 = mutableListOf<ReceivedEmail>()
    private val _bookingLinks          = mutableMapOf<String, String>() // token → link (Qualified)
    private val _clientAskLinks        = mutableMapOf<String, String>() // leadEmail → link
    private val _salesTeamAskLinks     = mutableMapOf<String, String>() // leadEmail → link
    private val _fallbackBookingLinks  = mutableMapOf<String, String>() // leadEmail → link

    override fun getOutbox(): List<SentEmail>                 = _outbox.toList()
    override fun getInbox(): List<ReceivedEmail>              = _inbox.toList()
    override fun getBookingLinks(): Map<String, String>       = _bookingLinks.toMap()
    override fun getClientAskBookingLinks(): Map<String, String>    = _clientAskLinks.toMap()
    override fun getSalesTeamAskBookingLinks(): Map<String, String>  = _salesTeamAskLinks.toMap()
    override fun getFallbackBookingLinks(): Map<String, String>      = _fallbackBookingLinks.toMap()

    override fun sendEmail(to: String, subject: String, body: String): String {
        val id = UUID.randomUUID().toString().take(8)
        _outbox.add(SentEmail(id, to, subject, body))
        println()
        println("  ┌─ EMAIL SENT ──────────────────────────────────────────────────")
        println("  │  TO:      $to")
        println("  │  SUBJECT: $subject")
        println("  │  ─────────────────────────────────────────────────────────────")
        body.lines().forEach { println("  │  $it") }
        println("  └───────────────────────────────────────────────────────────────")
        println()
        return id
    }

    override fun receiveEmail(from: String, subject: String, body: String) {
        _inbox.add(ReceivedEmail(from, subject, body))
    }

    override fun createBookingLink(leadName: String, company: String): String {
        val token = UUID.randomUUID().toString().take(8)
        val link = "https://booking.example.com/qualified/$token"
        _bookingLinks[token] = link
        println("✅ [MockEmailRepository] Qualified booking link for $leadName @ $company → $link")
        return link
    }

    override fun createClientAskBookingLink(leadEmail: String): String {
        val token = UUID.randomUUID().toString().take(8)
        val link = "https://booking.example.com/client-ask/$token"
        _clientAskLinks[leadEmail] = link
        println("🙋 [MockEmailRepository] ClientAsk booking link for $leadEmail → $link")
        return link
    }

    override fun createSalesTeamAskBookingLink(leadEmail: String): String {
        val token = UUID.randomUUID().toString().take(8)
        val link = "https://booking.example.com/sales-team/$token"
        _salesTeamAskLinks[leadEmail] = link
        println("🤝 [MockEmailRepository] SalesTeamAsk booking link for $leadEmail → $link")
        return link
    }

    override fun createFallbackBookingLink(leadEmail: String): String {
        val token = UUID.randomUUID().toString().take(8)
        val link = "https://booking.example.com/fallback/$token"
        _fallbackBookingLinks[leadEmail] = link
        println("🔁 [MockEmailRepository] Fallback (LLM-failed) booking link for $leadEmail → $link")
        return link
    }
}
