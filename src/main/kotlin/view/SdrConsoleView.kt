package org.example.view

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.orchestrator.SdrOrchestrator
import org.example.sdr.Lead
import org.example.sdr.LeadStatus
import java.util.UUID

/**
 * Interactive CLI for the AI SDR Agent.
 *
 * This is intentionally thin: it only reads input, calls [SdrOrchestrator],
 * and prints results. All business logic lives in the orchestrator and below.
 */
class SdrConsoleView(private val orchestrator: SdrOrchestrator) {

    suspend fun start() {
        printWelcome()
        var running = true
        while (running) {
            print("\nsdr> ")
            System.out.flush()

            val line = withContext(Dispatchers.IO) { readlnOrNull() }?.trim() ?: break
            val parts = line.split(" ", limit = 2)
            val cmd   = parts[0].lowercase()
            val arg   = parts.getOrNull(1)?.trim()

            running = when (cmd) {
                "new-lead"  -> { handleNewLead();                true }
                "reply"     -> { handleReply(arg);               true }
                "resolve"   -> { handleResolveEscalation(arg);   true }
                "leads"     -> { handleListLeads();              true }
                "timeline"  -> { handleTimeline(arg);            true }
                "lead"      -> { handleLeadDetail(arg);          true }
                "demo"      -> { runDemo();                      true }
                "help"      -> { printHelp();                    true }
                "exit", "quit" -> false
                ""          -> true
                else        -> { println("Unknown command: '$cmd'. Type 'help'."); true }
            }
        }
        println("\nGoodbye! 👋")
    }

    // ── Command handlers ──────────────────────────────────────────────────────

    private suspend fun handleNewLead() {
        val name    = prompt("Lead name")           ?: return
        val email   = prompt("Lead email")          ?: return
        val company = prompt("Lead company")        ?: return
        val message = prompt("Their initial message") ?: return

        val lead = Lead(
            id              = UUID.randomUUID().toString().take(8),
            name            = name,
            email           = email,
            company         = company,
            inboundMessage  = message
        )

        println("\n🔄 Processing new lead [${lead.id}]…")
        val result = orchestrator.processNewLead(lead)
        println("\n🤖 Agent: $result")
        println("\n💡 Lead ID: ${lead.id}  (use this in 'reply' and 'resolve' commands)")
    }

    private suspend fun handleReply(arg: String?) {
        val leadId = arg ?: run { println("Usage: reply <leadId>"); return }
        val lead   = orchestrator.getLead(leadId)
            ?: run { println("Lead '$leadId' not found. Use 'leads' to list all."); return }

        val reply = prompt("Reply from ${lead.name} (${lead.company})") ?: return

        println("\n🔄 Processing reply…")
        val result = orchestrator.processReply(leadId, reply)
        println("\n🤖 Agent: $result")
    }

    private suspend fun handleResolveEscalation(arg: String?) {
        val leadId = arg ?: run { println("Usage: resolve <leadId>"); return }
        val lead   = orchestrator.getLead(leadId)
            ?: run { println("Lead '$leadId' not found."); return }

        if (lead.status != LeadStatus.ESCALATED_HUMAN) {
            println("Lead '${lead.name}' is not escalated (status: ${lead.status}).")
            return
        }

        println("Escalation for ${lead.name}: ${lead.escalation?.reason}")
        println("Trigger: \"${lead.escalation?.triggerMessage}\"")

        val response = prompt("Your response as the human reviewer") ?: return

        println("\n🔄 Resuming agent with human input…")
        val result = orchestrator.resolveEscalation(leadId, response)
        println("\n🤖 Agent: $result")
    }

    private fun handleListLeads() {
        val leads = orchestrator.getLeads()
        if (leads.isEmpty()) { println("No leads yet. Use 'new-lead' or 'demo'."); return }

        println("\n┌─── Leads (${leads.size}) ─────────────────────────────────────────────")
        leads.forEach { lead ->
            val icon = statusIcon(lead.status)
            println("│ $icon [${lead.id}]  ${lead.name.padEnd(20)} @ ${lead.company.padEnd(20)} → ${lead.status}")
            if (lead.bookingLink != null) println("│    📅 ${lead.bookingLink}")
        }
        println("└──────────────────────────────────────────────────────────────────")
    }

    private fun handleLeadDetail(arg: String?) {
        val leadId = arg ?: run { println("Usage: lead <leadId>"); return }
        val lead   = orchestrator.getLead(leadId)
            ?: run { println("Lead '$leadId' not found."); return }
        println(lead.toContextString())
        println("Email thread (${lead.emailThread.size} messages):")
        lead.emailThread.forEachIndexed { i, msg ->
            val dir = if (msg.direction.name == "OUTBOUND") "→ SENT" else "← RECV"
            println("  [${i+1}] $dir  ${msg.subject}")
        }
    }

    private fun handleTimeline(arg: String?) = orchestrator.printAuditLog(arg)

    // ── Demo ──────────────────────────────────────────────────────────────────

    private suspend fun runDemo() {
        println("\n🎬 Running automated demo (3 scenarios)…")
        println("═".repeat(65))

        // ── Scenario 1: Happy path ────────────────────────────────────────────
        section("SCENARIO 1 — Happy path: well-qualified lead")

        val lead1 = Lead(
            id             = "demo-001",
            name           = "Sarah Chen",
            email          = "sarah.chen@techcorp.com",
            company        = "TechCorp Inc.",
            inboundMessage = "Hi, I saw your product at a conference. We're a 50-person engineering " +
                             "team looking for better project-management tooling. Interested in learning more."
        )
        demoStep("Processing new lead: ${lead1.name} @ ${lead1.company}")
        println(orchestrator.processNewLead(lead1))

        demoStep("Lead replies with full qualification data")
        val reply1 = "Thanks for reaching out! We need a tool for sprint planning and bug tracking. " +
                     "50 engineers, and we have budget approved for this quarter — actively evaluating options."
        println(orchestrator.processReply("demo-001", reply1))

        println("\n═".repeat(65))

        // ── Scenario 2: Pricing escalation + human resolution ────────────────
        section("SCENARIO 2 — Pricing escalation with human-in-the-loop")

        val lead2 = Lead(
            id             = "demo-002",
            name           = "Mike Johnson",
            email          = "mike@scaleup.io",
            company        = "ScaleUp.io",
            inboundMessage = "We're evaluating tools for our product team. Can you tell me more?"
        )
        demoStep("Processing new lead: ${lead2.name} @ ${lead2.company}")
        println(orchestrator.processNewLead(lead2))

        demoStep("Lead asks about pricing (triggers escalation)")
        val reply2 = "Thanks! Our team is 25 people focused on SaaS products. " +
                     "What's the pricing? Do you offer startup discounts?"
        println(orchestrator.processReply("demo-002", reply2))

        demoStep("Human resolves escalation")
        val humanResponse = "We offer a 20% startup discount for Series A and below. " +
                            "Mention this but only after confirming their use case and budget timeline."
        println(orchestrator.resolveEscalation("demo-002", humanResponse))

        println("\n═".repeat(65))

        // ── Scenario 3: Disqualified lead ─────────────────────────────────────
        section("SCENARIO 3 — Disqualified: team too small")

        val lead3 = Lead(
            id             = "demo-003",
            name           = "Alex Williams",
            email          = "alex@freelancer.dev",
            company        = "Freelancer.dev",
            inboundMessage = "Hi, I'm a freelance developer exploring project management tools."
        )
        demoStep("Processing new lead: ${lead3.name} @ ${lead3.company}")
        println(orchestrator.processNewLead(lead3))

        demoStep("Lead reveals it's a solo freelancer")
        val reply3 = "I work alone managing around 5 client projects. Just exploring what's out there, no rush."
        println(orchestrator.processReply("demo-003", reply3))

        println("\n═".repeat(65))
        println("\n📊 Final Lead Summary:")
        handleListLeads()
        println("\n📋 Full Audit Log:")
        orchestrator.printAuditLog()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun prompt(label: String): String? {
        print("  $label: ")
        System.out.flush()
        return withContext(Dispatchers.IO) { readlnOrNull() }?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: run { println("  (empty — cancelled)"); null }
    }

    private fun section(title: String) {
        println("\n📋 $title")
        println("─".repeat(65))
    }

    private fun demoStep(description: String) {
        println("\n⏩ $description")
    }

    private fun statusIcon(status: LeadStatus) = when (status) {
        LeadStatus.NEW              -> "🆕"
        LeadStatus.AWAITING_REPLY   -> "⏳"
        LeadStatus.QUALIFYING       -> "🔍"
        LeadStatus.ESCALATED_HUMAN  -> "🚨"
        LeadStatus.QUALIFIED        -> "✅"
        LeadStatus.DISQUALIFIED     -> "❌"
    }

    private fun printWelcome() {
        println("""
            ╔═══════════════════════════════════════════════════════╗
            ║            🤖  AI SDR Agent — RevAI Challenge         ║
            ╠═══════════════════════════════════════════════════════╣
            ║  Commands:                                            ║
            ║   new-lead          Submit a new inbound lead         ║
            ║   reply  <id>       Simulate a lead reply             ║
            ║   resolve <id>      Provide human escalation response ║
            ║   leads             List all leads with status        ║
            ║   lead   <id>       Show lead detail                  ║
            ║   timeline [id]     Show audit event timeline         ║
            ║   demo              Run automated 3-scenario demo     ║
            ║   help              Show this screen                  ║
            ║   exit              Quit                              ║
            ╚═══════════════════════════════════════════════════════╝
        """.trimIndent())
    }

    private fun printHelp() = printWelcome()
}
