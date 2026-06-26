package org.example.sdr_view

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.sdr_viewmodel.SdrViewModel

/**
 * SdrConsoleView — pure I/O layer.
 *
 * Dual-Thread design (MVI-compliant):
 *  - Thread 1 (input loop): reads stdin, dispatches intents to [SdrViewModel]. Long-running
 *    commands (new-lead, reply, resolve) are fire-and-forget — control returns to the prompt
 *    immediately so the user can issue the next command while the agent works.
 *  - Thread 2 (result printer): collects [SdrViewModel.resultsFlow] and renders each completed
 *    agent result with a visual separator, then reprints the `sdr>` prompt.
 *
 * The View never mutates state. All rendering is driven by data from the ViewModel.
 * State mutations happen exclusively in SdrRepository via deterministic Actions.
 */
class SdrConsoleView(private val viewModel: SdrViewModel) {

    suspend fun start() {
        printWelcome()
        coroutineScope {
            // Thread 2 — async result printer
            val resultPrinterJob = launch {
                viewModel.resultsFlow.collect { displayText ->
                    printAsyncResult(displayText)
                }
            }

            // Thread 1 — input loop
            var running = true
            while (running) {
                print("\nsdr> ")
                System.out.flush()
                val line  = withContext(Dispatchers.IO) { readlnOrNull() }?.trim() ?: break
                val parts = line.split(" ", limit = 2)
                val cmd   = parts[0].lowercase()
                val arg   = parts.getOrNull(1)?.trim()

                running = when (cmd) {
                    "new-lead"      -> { handleNewLead();                        true }
                    "reply"         -> { handleReply(arg);                       true }
                    "resolve"       -> { handleResolve(arg);                     true }
                    "leads"         -> { println(viewModel.getLeadsList());       true }
                    "lead"          -> { println(viewModel.getLeadDetail(arg ?: "")); true }
                    "events"      -> { println(viewModel.getTimeline(arg));     true }
                    "all-events"        -> { println(viewModel.getEventLog());        true }
                    "outbox"        -> { println(viewModel.getOutbox());          true }
                    "inbox"         -> { println(viewModel.getInbox());           true }
                    "links"         -> { println(viewModel.getBookingLinks());    true }
                    "interventions" -> { println(viewModel.getLeadsNeedingIntervention()); true }
                    "demo"          -> { println(viewModel.runDemo());            true }
                    "help"          -> { printWelcome();                          true }
                    "exit", "quit"  -> false
                    ""              -> true
                    else            -> { println("Unknown command: '$cmd'. Type 'help'."); true }
                }
            }

            resultPrinterJob.cancelAndJoin()
        }
        println("\nGoodbye! 👋")
    }

    // ── Async command handlers (fire-and-forget) ──────────────────────────────

    private suspend fun handleNewLead() {
        val name    = prompt("Lead name")             ?: return
        val email   = prompt("Lead email")            ?: return
        val company = prompt("Lead company")          ?: return
        val message = prompt("Their initial message") ?: return
        viewModel.submitNewLead(name, email, company, message)
        println("🔄 Processing… (result will appear when ready)")
    }

    private suspend fun handleReply(arg: String?) {
        val leadEmail = arg ?: run { println("Usage: reply <email>"); return }
        if (!viewModel.leadExists(leadEmail)) { println("Lead '$leadEmail' not found. Use 'leads'."); return }
        val reply = prompt("Lead reply text") ?: return
        viewModel.submitReply(leadEmail, reply)
        println("🔄 Processing reply… (result will appear when ready)")
    }

    private suspend fun handleResolve(arg: String?) {
        val leadEmail = arg ?: run { println("Usage: resolve <email>"); return }
        if (!viewModel.isLeadEscalated(leadEmail)) {
            println(viewModel.getLeadDetail(leadEmail)); return
        }
        println(viewModel.getLeadForEscalationPrompt(leadEmail))
        val response = prompt("Your response as human reviewer") ?: return
        viewModel.submitResolveEscalation(leadEmail, response)
        println("🔄 Resuming agent… (result will appear when ready)")
    }

    // ── Result printer (Thread 2 callback) ───────────────────────────────────

    /**
     * Prints a completed agent result with a visual separator.
     * Called from the result-printer coroutine — never blocks the input loop.
     * Reprints the `sdr>` prompt afterward so the user knows they can keep typing.
     */
    private fun printAsyncResult(displayText: String) {
        println()
        println("  ┌─ 🤖 Agent Result ──────────────────────────────────────────")
        displayText.lines().forEach { println("  │  $it") }
        println("  └────────────────────────────────────────────────────────────")
        print("\nsdr> ")
        System.out.flush()
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
            ║   leads             Show list all leads with status   ║
            ║   lead   <email>    Show lead detail                  ║
            ║   all-events        Show global system event log      ║
            ║   events <email>    Show event of lead                ║
            ║   outbox            Show all sent emails              ║
            ║   inbox             Show all received emails          ║
            ║   links             Show all booking links            ║
            ║   interventions     Leads needing human attention     ║
            ║   demo              Run automated scenarios demo      ║
            ║   help / exit                                         ║
            ╚═══════════════════════════════════════════════════════╝
        """.trimIndent())
    }
}
