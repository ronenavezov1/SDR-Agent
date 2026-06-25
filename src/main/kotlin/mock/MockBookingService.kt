package org.example.mock

import java.util.UUID

/** Simulates a calendar booking service (e.g. Calendly). */
class MockBookingService {
    fun createBookingLink(leadName: String, company: String): String {
        val token = UUID.randomUUID().toString().take(8)
        val link = "https://booking.example.com/meet/$token"
        println("📅 [MockBookingService] Booking link created for $leadName @ $company → $link")
        return link
    }
}
