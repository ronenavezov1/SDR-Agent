package org.example.repositiories

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.debug.DebugLogger
import org.example.llm.LlmClient
import org.example.llm.LlmSendException
import org.example.sdr.BookingLinkReason
import org.example.sdr.Event
import org.example.sdr.EmailDirection
import org.example.sdr.EmailMessage
import org.example.sdr.HumanEscalation
import org.example.sdr.Lead
import org.example.sdr.QualificationConfig
import org.example.sdr.LeadStatus
import org.example.sdr_orchestrator.OrchestratorContext
import org.example.sdr_orchestrator.SdrOrchestrator

/**
 * SdrRepository — single source of truth AND execution hub.
 *
 * Thread safety guarantees:
 *  - [_leads] is a [ConcurrentHashMap] — safe for concurrent reads and writes.
 *  - [_systemEvents] is a synchronized list — safe for concurrent appends.
 *  - A per-lead [Mutex] ensures only ONE orchestrator processes a given lead at a time.
 *  - [createContext] returns deep copies of [Lead] so each orchestrator works on its
 *    own isolated snapshot; [saveLead] atomically replaces the stored lead.
 */
class SdrRepository(
    private val llmClients: List<LlmClient>,
    val emailRepository: EmailRepository,
    private val qualificationConfig: QualificationConfig
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Thread-safe storage ───────────────────────────────────────────────────

    private val _leads        = ConcurrentHashMap<String, Lead>()
    private val _systemEvents = Collections.synchronizedList(mutableListOf<Event>())

    // ── Per-lead mutexes — prevents two orchestrators on the same lead ─────────

    private val _leadMutexes = ConcurrentHashMap<String, Mutex>()
    private fun leadMutex(email: String) = _leadMutexes.getOrPut(email) { Mutex() }

    // ── Reactive streams ──────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _results = MutableSharedFlow<ProcessingResult>(extraBufferCapacity = 32)
    val results: SharedFlow<ProcessingResult> = _results.asSharedFlow()

    // ── Leads ─────────────────────────────────────────────────────────────────

    fun save(lead: Lead) { _leads[lead.email] = lead }
    fun getLead(email: String): Lead? = _leads[email]
    fun getLeads(): List<Lead> = _leads.values.toList()

    // ── Events ────────────────────────────────────────────────────────────────

    suspend fun logEvent(event: Event) {
        _leads[event.leadEmail]?.events?.add(event)
        _systemEvents.add(event)
        _events.emit(event)
    }

    fun getEvents(): List<Event> = _systemEvents.toList()

    // ── Processing results ────────────────────────────────────────────────────

    private val _pendingResults = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Register a result slot BEFORE submitting work — guarantees no emission is missed. */
    fun registerResult(leadEmail: String): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()
        _pendingResults[leadEmail] = deferred
        return deferred
    }

    suspend fun emitResult(leadEmail: String, text: String) {
        val deferred = _pendingResults.remove(leadEmail)
        if (deferred != null) {
            // Caller is blocking with awaitResult (e.g. demo) — deliver directly, skip SharedFlow
            // to avoid double-printing in the dual-thread result printer.
            deferred.complete(text)
        } else {
            // Fire-and-forget path — publish to SharedFlow so the async result printer picks it up.
            _results.emit(ProcessingResult(leadEmail, text))
        }
    }

    // ── Work submission — per-lead mutex + fresh orchestrator per request ──────

    fun submitNewLead(lead: Lead) {
        scope.launch {
            leadMutex(lead.email).withLock {
                tryWithAllClients(lead.email) { ctx ->
                    SdrOrchestrator(ctx).process(newLead = lead, messageText = lead.inboundMessage)
                }
            }
        }
    }

    fun submitReply(leadEmail: String, replyText: String) {
        scope.launch {
            leadMutex(leadEmail).withLock {
                tryWithAllClients(leadEmail) { ctx ->
                    SdrOrchestrator(ctx).process(messageText = replyText)
                }
            }
        }
    }

    fun submitResolveEscalation(leadEmail: String, humanResponse: String) {
        scope.launch {
            leadMutex(leadEmail).withLock {
                tryWithAllClients(leadEmail) { ctx ->
                    SdrOrchestrator(ctx).resolveEscalation(humanResponse)
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun shutdown() = scope.cancel()

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun tryWithAllClients(
        leadEmail: String,
        block: suspend (OrchestratorContext) -> Unit
    ) {
        var lastLlmException: LlmSendException? = null
        for (client in llmClients) {
            try {
                block(createContext(leadEmail, client))
                return
            } catch (e: CancellationException) {
                throw e  // coroutine contract — always re-throw immediately
            } catch (e: LlmSendException) {
                DebugLogger.llmClientFailure(client::class.simpleName ?: "LLMClient", e.message)
                lastLlmException = e
            } catch (e: Exception) {
                DebugLogger.orchestrationBug(leadEmail, e)
                handleOrchestrationError(leadEmail, e)
                return
            }
        }
        handleOrchestrationError(leadEmail, lastLlmException ?: Exception("All LLM clients failed"))
    }

    private suspend fun handleOrchestrationError(leadEmail: String, e: Exception) {
        val reason = e.message ?: "Unknown error"
        val lead   = _leads[leadEmail]

        if (lead == null) {
            emitResult(leadEmail, "❌ All LLM clients failed for $leadEmail (lead not found): $reason")
            return
        }

        // Lead gets a fallback booking link and is marked terminal (ApprovedLlmFailed).
        // No agent decision was made — the link is stored separately from agent-qualified links.
        val fallbackLink = emailRepository.createFallbackBookingLink(leadEmail)
        lead.status = LeadStatus.ApprovedLlmFailed(fallbackLink)
        _leads[leadEmail] = lead
        logEvent(Event.BookingLinkCreated(leadEmail = leadEmail, bookingLink = fallbackLink, reason = BookingLinkReason.LlmFailed))

        // LLM is unavailable so farewell-writer cannot run.
        // Send a minimal hardcoded email so the lead always receives their booking link.
        val farewellSubject = "Next steps with our team"
        val farewellBody = """
            Hi ${lead.name},

            Thank you for reaching out. A member of our sales team will be in touch shortly.

            In the meantime, you can use the link below to schedule a call at a time that suits you:
            $fallbackLink

            Best regards,
            The Sales Team
        """.trimIndent()
        emailRepository.sendEmail(lead.email, farewellSubject, farewellBody)
        lead.emailThread.add(EmailMessage(EmailDirection.OUTBOUND, farewellSubject, farewellBody))
        _leads[leadEmail] = lead
        logEvent(Event.EmailSent(leadEmail = leadEmail, subject = farewellSubject, body = farewellBody))

        logEvent(Event.ProcessingFailed(leadEmail = leadEmail, reason = reason))
        emitResult(
            leadEmail,
            "⚠️ All LLM clients failed for $leadEmail — marked ApprovedLlmFailed. " +
            "Fallback farewell sent. Link: $fallbackLink  (visible under 'links')"
        )
    }

    private fun createContext(leadEmail: String, client: LlmClient): OrchestratorContext = object : OrchestratorContext {
        override val leadEmail: String = leadEmail
        override val llmClient: LlmClient = client
        override val config: QualificationConfig = this@SdrRepository.qualificationConfig

        override fun getLead(): Lead? = this@SdrRepository.getLead(leadEmail)?.deepCopy()

        override fun saveLead(lead: Lead) {
            require(lead.email == leadEmail) { "LeadScope violation: tried to save ${lead.email} from scope of $leadEmail" }
            this@SdrRepository.save(lead)
        }

        override suspend fun logEvent(event: Event) {
            require(event.leadEmail == leadEmail) {
                "LeadScope violation: tried to log event for ${event.leadEmail} from scope of $leadEmail"
            }
            this@SdrRepository.logEvent(event)
        }

        override suspend fun emitResult(text: String) =
            this@SdrRepository.emitResult(leadEmail, text)

        override fun sendEmail(to: String, subject: String, body: String): String =
            this@SdrRepository.emailRepository.sendEmail(to, subject, body)

        override fun createBookingLink(leadName: String, company: String): String =
            this@SdrRepository.emailRepository.createBookingLink(leadName, company)

        override fun createClientAskBookingLink(): String =
            this@SdrRepository.emailRepository.createClientAskBookingLink(leadEmail)

        override fun createSalesTeamAskBookingLink(): String =
            this@SdrRepository.emailRepository.createSalesTeamAskBookingLink(leadEmail)
    }
}

/** Carries the orchestrator's final text output for a specific lead. */
data class ProcessingResult(val leadEmail: String, val text: String)
