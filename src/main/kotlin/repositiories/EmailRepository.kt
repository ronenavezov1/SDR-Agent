package org.example.repositiories

data class SentEmail(
    val id: String,
    val to: String,
    val subject: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ReceivedEmail(
    val from: String,
    val subject: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

interface EmailRepository {
    fun sendEmail(to: String, subject: String, body: String): String
    /** Agent fully qualified the lead — all data present. */
    fun createBookingLink(leadName: String, company: String): String
    /** Lead asked to speak with a human representative. */
    fun createClientAskBookingLink(leadEmail: String): String
    /** Sales team instructed handoff but qualification data is incomplete. */
    fun createSalesTeamAskBookingLink(leadEmail: String): String
    /** All LLM clients failed — no agent decision was possible. */
    fun createFallbackBookingLink(leadEmail: String): String
    fun receiveEmail(from: String, subject: String, body: String)
    fun getOutbox(): List<SentEmail>
    fun getInbox(): List<ReceivedEmail>
    fun getBookingLinks(): Map<String, String>
    fun getClientAskBookingLinks(): Map<String, String>
    fun getSalesTeamAskBookingLinks(): Map<String, String>
    fun getFallbackBookingLinks(): Map<String, String>
}
