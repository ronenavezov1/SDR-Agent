package org.example.sdr_view

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.sdr_viewmodel.SdrViewModel

/**
 * SdrConsoleView — pure I/O layer.
 *
 * Reads stdin, delegates every command to [SdrViewModel], prints the result.
 * No business logic, no state, no domain knowledge lives here.
 */
class SdrConsoleView(private val viewModel: SdrViewModel) {

    suspend fun start() {
        printWelcome()
        var running = true
        while (running) {
            print("\nsdr> ")
            System.out.flush()
            val line  = withContext(Dispatchers.IO) { readlnOrNull() }?.trim() ?: break
            val parts = line.split(" ", limit = 2)
            val cmd   = parts[0].lowercase()
            val arg   = parts.getOrNull(1)?.trim()

            running = when (cmd) {
                "new-lead"     -> { handleNewLead();               true }
                "reply"        -> { handleReply(arg);              true }
                "resolve"      -> { handleResolve(arg);            true }
                "leads"        -> { println(viewModel.getLeadsList()); true }
                "lead"         -> { println(viewModel.getLeadDetail(arg ?: "")); true }
                "timeline"     -> { println(viewModel.getTimeline(arg)); true }
                "events"       -> { println(viewModel.getEventLog());    true }
                "outbox"       -> { println(viewModel.getOutbox());      true }
                "inbox"        -> { println(viewModel.getInbox());       true }
                "links"        -> { println(viewModel.getBookingLinks()); true }
                "interventions"-> { println(viewModel.getLeadsNeedingIntervention()); true }
                "demo"         -> { println(viewModel.runDemo());  true }
                "help"         -> { printWelcome();                true }
                "exit", "quit" -> false
                ""             -> true
                else           -> { println("Unknown command: '$cmd'. Type 'help'."); true }
            }
        }
        println("\nGoodbye! 👋")
    }

    // ── Input collectors ──────────────────────────────────────────────────────

    private suspend fun handleNewLead() {
        val name    = prompt("Lead name")             ?: return
        val email   = prompt("Lead email")            ?: return
        val company = prompt("Lead company")          ?: return
        val message = prompt("Their initial message") ?: return
        println("\n🔄 Processing…")
        println(viewModel.handleNewLead(name, email, company, message))
    }

    private suspend fun handleReply(arg: String?) {
        val leadEmail = arg ?: run { println("Usage: reply <email>"); return }
        if (!viewModel.leadExists(leadEmail)) { println("Lead '$leadEmail' not found. Use 'leads'."); return }
        val reply = prompt("Lead reply text") ?: return
        println("\n🔄 Processing reply…")
        println(viewModel.handleReply(leadEmail, reply))
    }

    private suspend fun handleResolve(arg: String?) {
        val leadEmail = arg ?: run { println("Usage: resolve <email>"); return }
        if (!viewModel.isLeadEscalated(leadEmail)) {
            println(viewModel.getLeadDetail(leadEmail)); return
        }
        println(viewModel.getLeadForEscalationPrompt(leadEmail))
        val response = prompt("Your response as human reviewer") ?: return
        println("\n🔄 Resuming agent…")
        println(viewModel.handleResolveEscalation(leadEmail, response))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun prompt(label: String): String? {
        print("  $label: ")
        System.out.flush()
        return withContext(Dispatchers.IO) { readlnOrNull() }?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: run { println("  (empty — cancelled)"); null }
    }

    private fun printWelcome() {
        println("""
            ╔═══════════════════════════════════════════════════════╗
            ║            🤖  AI SDR Agent — RevAI Challenge         ║
            ╠═══════════════════════════════════════════════════════╣
            ║   new-lead          Submit a new inbound lead         ║
            ║   reply  <email>    Simulate a lead reply             ║
            ║   resolve <email>   Provide human escalation response ║
            ║   leads             List all leads with status        ║
            ║   lead   <email>    Show lead detail                  ║
            ║   timeline [email]  Show event timeline               ║
            ║   events            Show global system event log      ║
            ║   outbox            Show all sent emails              ║
            ║   inbox             Show all received emails          ║
            ║   links             Show all booking links            ║
            ║   interventions     Leads needing human attention     ║
            ║   demo              Run automated 3-scenario demo     ║
            ║   help / exit                                         ║
            ╚═══════════════════════════════════════════════════════╝
        """.trimIndent())
    }
}
