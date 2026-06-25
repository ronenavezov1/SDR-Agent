package org.example.mock

import java.util.UUID

data class SentEmail(
    val id: String,
    val to: String,
    val subject: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** Simulates an email delivery service. Prints emails to stdout for demo visibility. */
class MockEmailService {
    private val _sent = mutableListOf<SentEmail>()
    val sentEmails: List<SentEmail> get() = _sent.toList()

    fun sendEmail(to: String, subject: String, body: String): String {
        val id = UUID.randomUUID().toString().take(8)
        _sent.add(SentEmail(id, to, subject, body))

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
}
